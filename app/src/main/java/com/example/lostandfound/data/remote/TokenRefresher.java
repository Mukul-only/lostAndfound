package com.example.lostandfound.data.remote;

import android.util.Log;
import com.example.lostandfound.data.model.AuthResponse;
import com.google.gson.Gson;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;

public class TokenRefresher implements ITokenRefresher {
    private static final String TAG = "TokenRefresher";
    private final ISessionManager sessionManager;
    private final OkHttpClient refreshHttpClient;
    private final Gson gson = new Gson();

    public TokenRefresher(ISessionManager sessionManager) {
        this.sessionManager = sessionManager;

        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BASIC);
        logging.redactHeader("apikey");
        logging.redactHeader("Authorization");

        this.refreshHttpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .build();
    }

    /**
     * Synchronously refreshes the access token using the stored refresh_token.
     * Thread-safe to prevent multiple concurrent refresh calls.
     * Returns the new access token, or null if refresh failed.
     */
    public synchronized String refreshTokenSync() {
        String refreshToken = sessionManager.getRefreshToken();
        if (refreshToken == null || refreshToken.trim().isEmpty()) {
            return null;
        }

        String baseUrl = SupabaseConfig.SUPABASE_URL;
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/";
        }
        String refreshUrl = baseUrl + "auth/v1/token?grant_type=refresh_token";

        Map<String, String> bodyMap = new HashMap<>();
        bodyMap.put("refresh_token", refreshToken);
        String jsonBody = gson.toJson(bodyMap);

        Request request = new Request.Builder()
                .url(refreshUrl)
                .header("apikey", SupabaseConfig.SUPABASE_PUBLISHABLE_KEY)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(MediaType.parse("application/json"), jsonBody))
                .build();

        try (Response response = refreshHttpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String responseStr = response.body().string();
                AuthResponse auth = gson.fromJson(responseStr, AuthResponse.class);
                if (auth != null && auth.getAccessToken() != null) {
                    sessionManager.updateTokens(
                            auth.getAccessToken(),
                            auth.getRefreshToken() != null ? auth.getRefreshToken() : refreshToken,
                            auth.getExpiresIn()
                    );
                    return auth.getAccessToken();
                }
            } else {
                Log.w(TAG, "Refresh token request failed with HTTP " + response.code());
                // If the refresh token is expired or revoked (400 or 401), clear the invalid session
                if (response.code() == 400 || response.code() == 401) {
                    sessionManager.clearSession();
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Network error during token refresh", e);
        }

        return null;
    }
}
