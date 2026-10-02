package com.example.lostandfound.util;

/**
 * Pure helpers for profile editing: display-name validation
 * avatar placeholders, and the fixed per-user avatar storage path.
 * Fixed path (<uid>/avatar.jpg) + server-side upsert means replacing a
 * photo overwrites in place and never accumulates orphaned uploads.
 */
public final class ProfileUtils {

    private ProfileUtils() {}

    public static final int MIN_NAME_LENGTH = 2;
    public static final int MAX_NAME_LENGTH = 60;

    public static final int AVATAR_MAX_DIMENSION_PX = 512;
    public static final int AVATAR_JPEG_QUALITY = 80;
    public static final String AVATAR_FILE_NAME = "avatar.jpg";
    public static final String AVATAR_CONTENT_TYPE = "image/jpeg";

    /**
     * Validates a display name. Returns an error message, or null when valid.
     * Rule: non-blank after trim, {@value #MIN_NAME_LENGTH}..{@value #MAX_NAME_LENGTH} chars.
     */
    public static String validateDisplayName(String rawName) {
        if (rawName == null || rawName.trim().isEmpty()) {
            return "Please enter a display name.";
        }
        String trimmed = rawName.trim();
        if (trimmed.length() < MIN_NAME_LENGTH) {
            return "Display name must be at least " + MIN_NAME_LENGTH + " characters.";
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            return "Display name must be at most " + MAX_NAME_LENGTH + " characters.";
        }
        return null;
    }


    /**
     * Fixed storage path for a user's avatar. Same path on every upload so
     * replacements overwrite instead of creating new objects.
     */
    public static String avatarPathFor(String userId) {
        return userId + "/" + AVATAR_FILE_NAME;
    }
}
