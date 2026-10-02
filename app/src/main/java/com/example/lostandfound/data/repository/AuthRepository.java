package com.example.lostandfound.data.repository;

import android.content.Context;
import com.example.lostandfound.data.model.AuthResponse;
import com.example.lostandfound.data.model.Profile;
import com.example.lostandfound.data.remote.SessionManager;
import com.example.lostandfound.data.remote.SupabaseClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import okhttp3.ResponseBody;
import org.json.JSONObject;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class AuthRepository {
    public interface AuthCallback {
        void onSuccess(boolean emailConfirmationRequired);
        void onError(String message);
    }

    private final SupabaseClient client;
    private final SessionManager sessionManager;
    private final Context appContext;

    public AuthRepository(Context context) {
        this.appContext = context.getApplicationContext();
        this.client = SupabaseClient.getInstance(context);
        this.sessionManager = SessionManager.getInstance(context);
    }

    public boolean isLoggedIn() {
        return sessionManager.isLoggedIn();
    }

    public String getCurrentUserId() {
        return sessionManager.getUserId();
    }

    public String getCurrentUserEmail() {
        return sessionManager.getUserEmail();
    }

    public String getCurrentDisplayName() {
        return sessionManager.getDisplayName();
    }

    public void signUp(String fullName, String email, String password, AuthCallback callback) {
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("password", password);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("full_name", fullName);
        body.put("data", metadata);

        client.getAuthService().signUp(body).enqueue(new Callback<AuthResponse>() {
            @Override
            public void onResponse(Call<AuthResponse> call, Response<AuthResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    AuthResponse auth = response.body();
                    // In Supabase, if email confirmation is enabled, access_token is null on sign up
                    if (auth.getAccessToken() != null && !auth.getAccessToken().isEmpty()) {
                        sessionManager.saveSession(auth, email);
                        // Save profile
                        syncProfile(auth.getUser().getId(), fullName);
                        callback.onSuccess(false);
                    } else {
                        // User created, confirmation email sent
                        callback.onSuccess(true);
                    }
                } else {
                    String error = parseAuthError(response, "Registration failed");
                    callback.onError(error);
                }
            }

            @Override
            public void onFailure(Call<AuthResponse> call, Throwable t) {
                callback.onError("Network error: " + t.getLocalizedMessage());
            }
        });
    }

    public void signIn(String email, String password, AuthCallback callback) {
        Map<String, String> body = new HashMap<>();
        body.put("email", email);
        body.put("password", password);

        client.getAuthService().signIn(body).enqueue(new Callback<AuthResponse>() {
            @Override
            public void onResponse(Call<AuthResponse> call, Response<AuthResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    AuthResponse auth = response.body();
                    sessionManager.saveSession(auth, email);
                    // Fetch or sync profile
                    fetchAndSyncProfile(auth.getUser().getId());
                    callback.onSuccess(false);
                } else {
                    String error = parseAuthError(response, "Sign in failed. Invalid email or password.");
                    callback.onError(error);
                }
            }

            @Override
            public void onFailure(Call<AuthResponse> call, Throwable t) {
                callback.onError("Network error: " + t.getLocalizedMessage());
            }
        });
    }

    public void logout(Runnable onComplete) {
        sessionManager.clearSession();
        // Cached display names/avatars must not survive into another account.
        ProfileDirectory.getInstance().clear();
        // Unread counts are per-account too. Left in place they would put the
        // previous user's badge on the next account's nav bar.
        UnreadRepository.getInstance(appContext).reset();
        try {
            client.getAuthService().logout().enqueue(new Callback<ResponseBody>() {
                @Override
                public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                    if (onComplete != null) onComplete.run();
                }

                @Override
                public void onFailure(Call<ResponseBody> call, Throwable t) {
                    if (onComplete != null) onComplete.run();
                }
            });
        } catch (Exception e) {
            if (onComplete != null) onComplete.run();
        }
    }

    private void syncProfile(String userId, String fullName) {
        Profile profile = new Profile(userId, fullName);
        client.getRestService().upsertProfile("resolution=merge-duplicates", profile).enqueue(new Callback<List<Profile>>() {
            @Override
            public void onResponse(Call<List<Profile>> call, Response<List<Profile>> response) {}
            @Override
            public void onFailure(Call<List<Profile>> call, Throwable t) {}
        });
    }

    private void fetchAndSyncProfile(String userId) {
        client.getRestService().getProfiles("*", "eq." + userId).enqueue(new Callback<List<Profile>>() {
            @Override
            public void onResponse(Call<List<Profile>> call, Response<List<Profile>> response) {
                if (response.isSuccessful() && response.body() != null && !response.body().isEmpty()) {
                    sessionManager.saveProfile(response.body().get(0));
                }
            }

            @Override
            public void onFailure(Call<List<Profile>> call, Throwable t) {}
        });
    }

    private String parseAuthError(Response<?> response, String defaultMessage) {
        if (response.errorBody() != null) {
            try {
                String rawJson = response.errorBody().string();
                JSONObject obj = new JSONObject(rawJson);
                String errorCode = obj.optString("error_code", "");
                String msg = obj.optString("msg", obj.optString("message", obj.optString("error_description", "")));

                if ("over_email_send_rate_limit".equalsIgnoreCase(errorCode) || rawJson.contains("over_email_send_rate_limit")) {
                    return "Supabase email rate limit exceeded (429). To fix this for development: in Supabase Dashboard go to Authentication -> Providers -> Email and turn OFF 'Confirm email', then try signing up again.";
                }

                if (!msg.isEmpty()) {
                    return msg;
                }
                return rawJson;
            } catch (Exception ignored) {}
        }
        return defaultMessage + " (Code: " + response.code() + ")";
    }
}
