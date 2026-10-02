package com.example.lostandfound.data.remote;

import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;

/**
 * Supabase configuration constants.
 *
 * NOTE: Replace SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY with your project's credentials
 * from Supabase Dashboard -> Project Settings -> API.
 * Use the publishable key (e.g. "sb_publishable_...").
 * NEVER place your service_role secret key (e.g. "sb_secret_...") in the Android app!
 */
public class SupabaseConfig {
    // Replace with your Supabase Project URL (e.g. "https://abcdefghijklmnopqrst.supabase.co/")
    public static final String SUPABASE_URL = "https://caqiibpmszbuektdeude.supabase.co";

    // Replace with your Supabase public publishable key (e.g. "sb_publishable_...")
    public static final String SUPABASE_PUBLISHABLE_KEY = "sb_publishable_vK4m87kNEP55WmXSYohRtQ_h7-_CVKY";

    public static boolean isConfigured() {
        if (SUPABASE_URL == null || SUPABASE_PUBLISHABLE_KEY == null) {
            return false;
        }
        String url = SUPABASE_URL.trim();
        String key = SUPABASE_PUBLISHABLE_KEY.trim();

        boolean isUrlValid = (url.startsWith("https://") || url.startsWith("http://"))
                && !url.contains("your-project")
                && !url.contains("YOUR_PROJECT");

        boolean isKeyValid = !key.isEmpty()
                && !key.contains("your-publishable-key")
                && !key.contains("YOUR_KEY")
                && (key.startsWith("sb_publishable_") || key.startsWith("eyJ") || key.length() >= 20);

        return isUrlValid && isKeyValid;
    }

    public static String getPublicImageUrl(String storagePath) {
        if (storagePath == null) {
            return null;
        }
        String path = storagePath.trim();
        if (path.isEmpty()) {
            return null;
        }
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }

        // Strip leading slashes
        while (path.startsWith("/")) {
            path = path.substring(1);
        }

        // Normalize redundant bucket / API path prefixes if previously saved
        if (path.startsWith("storage/v1/object/public/report-photos/")) {
            path = path.substring("storage/v1/object/public/report-photos/".length());
        } else if (path.startsWith("report-photos/")) {
            path = path.substring("report-photos/".length());
        }

        while (path.startsWith("/")) {
            path = path.substring(1);
        }

        if (path.isEmpty()) {
            return null;
        }

        String baseUrl = SUPABASE_URL != null ? SUPABASE_URL.trim() : "";
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/";
        }
        return baseUrl + "storage/v1/object/public/report-photos/" + path;
    }

    public static String getPublicAvatarUrl(String storagePath) {
        if (storagePath == null) {
            return null;
        }
        String path = storagePath.trim();
        if (path.isEmpty()) {
            return null;
        }
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }

        while (path.startsWith("/")) {
            path = path.substring(1);
        }

        if (path.startsWith("storage/v1/object/public/avatars/")) {
            path = path.substring("storage/v1/object/public/avatars/".length());
        } else if (path.startsWith("avatars/")) {
            path = path.substring("avatars/".length());
        }

        while (path.startsWith("/")) {
            path = path.substring(1);
        }

        if (path.isEmpty()) {
            return null;
        }

        String baseUrl = SUPABASE_URL != null ? SUPABASE_URL.trim() : "";
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/";
        }
        return baseUrl + "storage/v1/object/public/avatars/" + path;
    }

    public static GlideUrl getGlideUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.trim().isEmpty()) {
            return null;
        }
        String key = SUPABASE_PUBLISHABLE_KEY != null ? SUPABASE_PUBLISHABLE_KEY.trim() : "";
        if (!key.isEmpty()) {
            return new GlideUrl(imageUrl, new LazyHeaders.Builder()
                    .addHeader("apikey", key)
                    .build());
        }
        return new GlideUrl(imageUrl);
    }
}
