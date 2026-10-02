package com.example.lostandfound.data.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import com.example.lostandfound.data.model.AuthResponse;
import com.example.lostandfound.data.model.Profile;

public class SessionManager implements ISessionManager {
    private static final String TAG = "SessionManager";
    private static final String PREF_NAME = "lost_found_secure_session";

    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_REFRESH_TOKEN = "refresh_token";
    private static final String KEY_EXPIRES_AT = "expires_at";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_USER_EMAIL = "user_email";
    private static final String KEY_DISPLAY_NAME = "display_name";
    private static final String KEY_AVATAR_PATH = "avatar_path";

    private static SessionManager instance;
    private final SharedPreferences prefs;

    private SessionManager(Context context) {
        SharedPreferences sharedPrefs;
        try {
            MasterKey masterKey = new MasterKey.Builder(context.getApplicationContext())
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            sharedPrefs = EncryptedSharedPreferences.create(
                    context.getApplicationContext(),
                    PREF_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize EncryptedSharedPreferences, falling back to standard prefs", e);
            sharedPrefs = context.getApplicationContext().getSharedPreferences(PREF_NAME + "_fallback", Context.MODE_PRIVATE);
        }
        this.prefs = sharedPrefs;
    }

    public static synchronized SessionManager getInstance(Context context) {
        if (instance == null) {
            instance = new SessionManager(context);
        }
        return instance;
    }

    public void saveSession(AuthResponse authResponse, String email) {
        if (authResponse == null) return;

        SharedPreferences.Editor editor = prefs.edit();
        if (authResponse.getAccessToken() != null) {
            editor.putString(KEY_ACCESS_TOKEN, authResponse.getAccessToken());
        }
        if (authResponse.getRefreshToken() != null) {
            editor.putString(KEY_REFRESH_TOKEN, authResponse.getRefreshToken());
        }
        if (authResponse.getExpiresIn() != null) {
            long expiresAt = System.currentTimeMillis() + (authResponse.getExpiresIn() * 1000L);
            editor.putLong(KEY_EXPIRES_AT, expiresAt);
        }
        if (authResponse.getUser() != null) {
            if (authResponse.getUser().getId() != null) {
                editor.putString(KEY_USER_ID, authResponse.getUser().getId());
            }
            if (authResponse.getUser().getEmail() != null) {
                editor.putString(KEY_USER_EMAIL, authResponse.getUser().getEmail());
            }
            if (authResponse.getUser().getUserMetadata() != null 
                    && authResponse.getUser().getUserMetadata().containsKey("full_name")) {
                Object nameObj = authResponse.getUser().getUserMetadata().get("full_name");
                if (nameObj != null) {
                    editor.putString(KEY_DISPLAY_NAME, nameObj.toString());
                }
            }
        }
        if (email != null && !email.trim().isEmpty()) {
            editor.putString(KEY_USER_EMAIL, email);
        }
        editor.apply();
    }

    public void updateTokens(String accessToken, String refreshToken, Long expiresIn) {
        SharedPreferences.Editor editor = prefs.edit();
        if (accessToken != null) {
            editor.putString(KEY_ACCESS_TOKEN, accessToken);
        }
        if (refreshToken != null) {
            editor.putString(KEY_REFRESH_TOKEN, refreshToken);
        }
        if (expiresIn != null) {
            long expiresAt = System.currentTimeMillis() + (expiresIn * 1000L);
            editor.putLong(KEY_EXPIRES_AT, expiresAt);
        }
        editor.apply();
    }

    public void saveProfile(Profile profile) {
        if (profile == null) return;
        SharedPreferences.Editor editor = prefs.edit();
        if (profile.getFullName() != null) {
            editor.putString(KEY_DISPLAY_NAME, profile.getFullName());
        }
        if (profile.getAvatarUrl() != null) {
            editor.putString(KEY_AVATAR_PATH, profile.getAvatarUrl());
        }
        editor.apply();
    }

    public boolean isLoggedIn() {
        return getAccessToken() != null && getUserId() != null;
    }

    public boolean isTokenExpired() {
        long expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0);
        // If expiresAt is set, treat as expired 60 seconds before actual expiry to refresh proactively
        return expiresAt > 0 && System.currentTimeMillis() >= (expiresAt - 60000L);
    }

    public long getExpiresAt() {
        return prefs.getLong(KEY_EXPIRES_AT, 0);
    }

    public String getAccessToken() {
        return prefs.getString(KEY_ACCESS_TOKEN, null);
    }

    public String getRefreshToken() {
        return prefs.getString(KEY_REFRESH_TOKEN, null);
    }

    public String getUserId() {
        return prefs.getString(KEY_USER_ID, null);
    }

    public String getUserEmail() {
        return prefs.getString(KEY_USER_EMAIL, "student@campus.edu");
    }

    public String getDisplayName() {
        return prefs.getString(KEY_DISPLAY_NAME, "Campus Student");
    }

    public String getAvatarPath() {
        return prefs.getString(KEY_AVATAR_PATH, null);
    }

    public void clearAvatarPath() {
        prefs.edit().remove(KEY_AVATAR_PATH).apply();
    }

    public void clearSession() {
        prefs.edit().clear().apply();
    }

    // Testing helpers
    public void setExpiresAtForTesting(long timestampMillis) {
        prefs.edit().putLong(KEY_EXPIRES_AT, timestampMillis).apply();
    }

    public void setAccessTokenForTesting(String token) {
        prefs.edit().putString(KEY_ACCESS_TOKEN, token).apply();
    }

    public void setRefreshTokenForTesting(String token) {
        prefs.edit().putString(KEY_REFRESH_TOKEN, token).apply();
    }

    public void setUserIdForTesting(String userId) {
        prefs.edit().putString(KEY_USER_ID, userId).apply();
    }
}
