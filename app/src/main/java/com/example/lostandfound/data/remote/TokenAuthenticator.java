package com.example.lostandfound.data.remote;

import java.io.IOException;
import okhttp3.Authenticator;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;

public class TokenAuthenticator implements Authenticator {
    private final ISessionManager sessionManager;
    private final ITokenRefresher tokenRefresher;

    public TokenAuthenticator(ISessionManager sessionManager, ITokenRefresher tokenRefresher) {
        this.sessionManager = sessionManager;
        this.tokenRefresher = tokenRefresher;
    }

    @Override
    public Request authenticate(Route route, Response response) throws IOException {
        // Prevent infinite loops: if this request has already been retried, give up
        if (responseCount(response) >= 2) {
            return null;
        }

        String path = response.request().url().encodedPath();
        // Do not attempt token refresh for authentication requests (signup, sign-in, token refresh itself)
        if (path.contains("auth/v1/")) {
            return null;
        }

        synchronized (this) {
            String currentToken = sessionManager.getAccessToken();
            String authHeader = response.request().header("Authorization");

            // If another thread already refreshed the token while this request was inflight:
            if (authHeader != null && currentToken != null && !authHeader.endsWith(currentToken)
                    && !currentToken.startsWith("sb_publishable_")) {
                return response.request().newBuilder()
                        .header("Authorization", "Bearer " + currentToken)
                        .build();
            }

            // Attempt synchronous refresh
            if (tokenRefresher != null) {
                String newToken = tokenRefresher.refreshTokenSync();
                if (newToken != null && !newToken.isEmpty() && !newToken.startsWith("sb_publishable_")) {
                    return response.request().newBuilder()
                            .header("Authorization", "Bearer " + newToken)
                            .build();
                }
            }
        }

        return null;
    }

    private int responseCount(Response response) {
        int count = 1;
        while ((response = response.priorResponse()) != null) {
            count++;
        }
        return count;
    }
}
