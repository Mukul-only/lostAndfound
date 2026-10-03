package com.example.lostandfound.data.repository;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import com.example.lostandfound.BuildConfig;
import com.example.lostandfound.data.model.Profile;
import com.example.lostandfound.data.remote.SessionManager;
import com.example.lostandfound.data.remote.SupabaseClient;
import com.example.lostandfound.util.ProfileUtils;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Own-profile reads and edits. All writes target ONLY the signed-in user's
 * row/folder: profile updates go through PATCH ...?id=eq.&lt;uid&gt; (the
 * "Users can update their own profile" RLS policy enforces ownership on the
 * server), and avatar uploads go to &lt;uid&gt;/avatar.jpg (storage RLS
 * enforces the folder). The UI must never offer another user's profile.
 */
public class ProfileRepository {
    public interface DataCallback<T> {
        void onSuccess(T data);
        void onError(String message);
    }

    private final SupabaseClient client;
    private final SessionManager sessionManager;

    public ProfileRepository(Context context) {
        this.client = SupabaseClient.getInstance(context);
        this.sessionManager = SessionManager.getInstance(context);
    }

    public void fetchMyProfile(DataCallback<Profile> callback) {
        String userId = sessionManager.getUserId();
        if (userId == null) {
            callback.onError("User not signed in");
            return;
        }

        client.getRestService().getProfiles("*", "eq." + userId).enqueue(new Callback<List<Profile>>() {
            @Override
            public void onResponse(Call<List<Profile>> call, Response<List<Profile>> response) {
                if (response.isSuccessful() && response.body() != null && !response.body().isEmpty()) {
                    Profile profile = response.body().get(0);
                    sessionManager.saveProfile(profile);
                    callback.onSuccess(profile);
                } else {
                    callback.onError("Could not load profile. Code: " + response.code());
                }
            }

            @Override
            public void onFailure(Call<List<Profile>> call, Throwable t) {
                callback.onError("Network error: " + t.getLocalizedMessage());
            }
        });
    }

    /**
     * Saves a new display name, optionally with a new avatar image.
     * When avatar bytes are provided, the storage upload MUST succeed first;
     * only then is profiles.avatar_url updated, so a failed upload can never
     * leave a broken avatar reference in the database.
     */
    public void saveProfile(String displayName, byte[] avatarBytes, DataCallback<Profile> callback) {
        String userId = sessionManager.getUserId();
        if (userId == null) {
            callback.onError("User not signed in");
            return;
        }

        if (avatarBytes != null) {
            uploadAvatar(userId, avatarBytes, new DataCallback<String>() {
                @Override
                public void onSuccess(String storagePath) {
                    patchProfile(userId, displayName, storagePath, callback);
                }

                @Override
                public void onError(String message) {
                    callback.onError(message);
                }
            });
        } else {
            patchProfile(userId, displayName, null, callback);
        }
    }

    private void patchProfile(String userId, String displayName, String avatarPath, DataCallback<Profile> callback) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("full_name", displayName);
        if (avatarPath != null) {
            fields.put("avatar_url", avatarPath);
        }

        client.getRestService().updateProfile("eq." + userId, "return=representation", fields)
                .enqueue(new Callback<List<Profile>>() {
                    @Override
                    public void onResponse(Call<List<Profile>> call, Response<List<Profile>> response) {
                        if (response.isSuccessful() && response.body() != null && !response.body().isEmpty()) {
                            Profile updated = response.body().get(0);
                            sessionManager.saveProfile(updated);
                            // ProfileDirectory caches every profile by id and never
                            // refetches one it already holds, so without this the
                            // old name/photo stayed on every feed, claim, chat and
                            // profile row until the process restarted. The PATCH
                            // asked for return=representation, so push the
                            // authoritative row straight into the cache.
                            ProfileDirectory.getInstance().put(updated);
                            callback.onSuccess(updated);
                        } else {
                            String error = "Failed to save profile.";
                            try {
                                if (response.errorBody() != null) {
                                    error = response.errorBody().string();
                                }
                            } catch (Exception ignored) {}
                            callback.onError(error);
                        }
                    }

                    @Override
                    public void onFailure(Call<List<Profile>> call, Throwable t) {
                        callback.onError("Network error: " + t.getLocalizedMessage());
                    }
                });
    }

    /** Clears the caller's profile photo.
     *
     *  <p>Writes an empty string rather than null: {@code avatar_url} is nullable,
     *  but {@link SessionManager#saveProfile} only writes the cached path when the
     *  returned profile carries a non-null {@code avatar_url}, so a null would
     *  clear the column while leaving a stale photo on the device.
     *  {@link SupabaseConfig#getPublicAvatarUrl} maps "" to null, so the UI falls
     *  back to the placeholder.
     *
     *  <p>The stored object is left in place. It used to be safe to do that
     *  because the avatar path was a fixed key that a later upload overwrote;
     *  paths are now unique per upload, so this object is genuinely orphaned and
     *  deleting it needs a storage DELETE call that does not exist yet. */
    public void removeAvatar(DataCallback<Profile> callback) {
        String userId = sessionManager.getUserId();
        if (userId == null) {
            callback.onError("User not signed in");
            return;
        }

        Map<String, Object> fields = new HashMap<>();
        fields.put("avatar_url", "");

        client.getRestService().updateProfile("eq." + userId, "return=representation", fields)
                .enqueue(new Callback<List<Profile>>() {
                    @Override
                    public void onResponse(Call<List<Profile>> call, Response<List<Profile>> response) {
                        if (response.isSuccessful() && response.body() != null && !response.body().isEmpty()) {
                            Profile updated = response.body().get(0);
                            sessionManager.saveProfile(updated);
                            // Belt and braces: an empty avatar_url would not clear
                            // the cached path through saveProfile's null check.
                            sessionManager.clearAvatarPath();
                            ProfileDirectory.getInstance().put(updated);
                            callback.onSuccess(updated);
                        } else {
                            String error = "Failed to remove photo.";
                            try {
                                if (response.errorBody() != null) {
                                    error = response.errorBody().string();
                                }
                            } catch (Exception ignored) {}
                            callback.onError(error);
                        }
                    }

                    @Override
                    public void onFailure(Call<List<Profile>> call, Throwable t) {
                        callback.onError("Network error: " + t.getLocalizedMessage());
                    }
                });
    }

    private void uploadAvatar(String userId, byte[] imageBytes, DataCallback<String> callback) {
        String storagePath = ProfileUtils.avatarPathFor(userId);
        RequestBody body = RequestBody.create(MediaType.parse(ProfileUtils.AVATAR_CONTENT_TYPE), imageBytes);

        if (BuildConfig.DEBUG) {
            Log.d("ProfileRepository", "Uploading avatar: " + storagePath + " (" + imageBytes.length + " bytes)");
        }

        client.getStorageService().uploadAvatar(storagePath, ProfileUtils.AVATAR_CONTENT_TYPE, "true", body)
                .enqueue(new Callback<ResponseBody>() {
                    @Override
                    public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                        if (response.isSuccessful()) {
                            callback.onSuccess(storagePath);
                        } else {
                            String error = "Photo upload failed: " + response.code();
                            try {
                                if (response.errorBody() != null) {
                                    error = response.errorBody().string();
                                }
                            } catch (Exception ignored) {}
                            if (BuildConfig.DEBUG) {
                                Log.w("ProfileRepository", "Avatar upload failed: " + error);
                            }
                            callback.onError(error);
                        }
                    }

                    @Override
                    public void onFailure(Call<ResponseBody> call, Throwable t) {
                        callback.onError("Photo upload failed: " + t.getLocalizedMessage());
                    }
                });
    }

    /**
     * Decodes a picked image, applies EXIF orientation, downscales so the
     * longest side is at most 512px, and compresses to JPEG (~80 quality).
     * Throws IOException on unreadable input; callers surface it as an error
     * state without touching any stored profile data.
     */
    public byte[] processAvatarImage(Context context, Uri uri) throws IOException {
        Bitmap bitmap;
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder honors EXIF orientation automatically.
            ImageDecoder.Source source = ImageDecoder.createSource(context.getContentResolver(), uri);
            bitmap = ImageDecoder.decodeBitmap(source);
        } else {
            InputStream input = context.getContentResolver().openInputStream(uri);
            if (input == null) {
                throw new IOException("Could not open selected photo");
            }
            try {
                bitmap = BitmapFactory.decodeStream(input);
            } finally {
                try {
                    input.close();
                } catch (IOException ignored) {}
            }
            if (bitmap == null) {
                throw new IOException("Could not decode selected photo");
            }
            bitmap = applyExifOrientation(context, uri, bitmap);
        }

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int maxDim = ProfileUtils.AVATAR_MAX_DIMENSION_PX;
        if (width > maxDim || height > maxDim) {
            float ratio = (float) width / height;
            if (ratio > 1) {
                width = maxDim;
                height = Math.round(maxDim / ratio);
            } else {
                height = maxDim;
                width = Math.round(maxDim * ratio);
            }
            bitmap = Bitmap.createScaledBitmap(bitmap, width, height, true);
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, ProfileUtils.AVATAR_JPEG_QUALITY, output)) {
            throw new IOException("Could not compress selected photo");
        }
        return output.toByteArray();
    }

    private Bitmap applyExifOrientation(Context context, Uri uri, Bitmap bitmap) {
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        InputStream input = null;
        try {
            input = context.getContentResolver().openInputStream(uri);
            if (input != null) {
                ExifInterface exif = new ExifInterface(input);
                orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            }
        } catch (Exception ignored) {
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {}
            }
        }

        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.postRotate(90);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.postRotate(180);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.postRotate(270);
                break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.postScale(1, -1);
                break;
            default:
                return bitmap;
        }
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (rotated != bitmap) {
            bitmap.recycle();
        }
        return rotated;
    }
}
