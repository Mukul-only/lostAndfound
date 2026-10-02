package com.example.lostandfound.data.remote;

import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

public class SupabaseAuthInterceptor implements Interceptor {
    private final ISessionManager sessionManager;
    private final ITokenRefresher tokenRefresher;

    public SupabaseAuthInterceptor(ISessionManager sessionManager, ITokenRefresher tokenRefresher) {
        this.sessionManager = sessionManager;
        this.tokenRefresher = tokenRefresher;
    }

    public SupabaseAuthInterceptor(ISessionManager sessionManager) {
        this(sessionManager, null);
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request original = chain.request();
        Request.Builder builder = original.newBuilder()
                .header("apikey", SupabaseConfig.SUPABASE_PUBLISHABLE_KEY);

        String path = original.url().encodedPath();
        boolean isAuthEndpoint = path.contains("auth/v1/token") || path.contains("auth/v1/signup");

        if (!isAuthEndpoint && sessionManager.isLoggedIn()) {
            // If the access token is close to expiry, proactively refresh before sending the request
            if (sessionManager.isTokenExpired() && tokenRefresher != null) {
                tokenRefresher.refreshTokenSync();
            }

            String token = sessionManager.getAccessToken();
            // Send Authorization: Bearer ONLY with a valid user access token.
            // Never send the publishable key as a Bearer token!
            if (token != null && !token.trim().isEmpty() && !token.startsWith("sb_publishable_")) {
                builder.header("Authorization", "Bearer " + token);
            }
        }

        // Unauthenticated requests (e.g. signup, sign-in, public catalog)
        // send ONLY the apikey header without an Authorization header.

        return chain.proceed(builder.build());
    }
}
