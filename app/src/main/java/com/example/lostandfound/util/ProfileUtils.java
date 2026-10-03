package com.example.lostandfound.util;

/**
 * Pure helpers for profile editing: display-name validation
 * avatar placeholders, and the fixed per-user avatar storage path.
 * Fixed path (<uid>/avatar.jpg) + server-side upsert means replacing a
 * photo overwrites in place and never accumulates orphaned uploads.
 */
import java.util.UUID;

public final class ProfileUtils {

    private ProfileUtils() {}

    public static final int MIN_NAME_LENGTH = 2;
    public static final int MAX_NAME_LENGTH = 60;

    public static final int AVATAR_MAX_DIMENSION_PX = 512;
    public static final int AVATAR_JPEG_QUALITY = 80;
    public static final String AVATAR_FILE_PREFIX = "avatar-";
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
     * Storage path for a user's avatar, unique per upload.
     *
     * <p>This used to be a fixed {@code <uid>/avatar.jpg} so replacements would
     * overwrite rather than accumulate. That broke photo updates: overwriting
     * leaves the public URL byte-identical, so both Glide (keyed by URL) and the
     * storage CDN kept serving the previous image, and the new photo only appeared
     * after a cache-cold restart. Varying the filename changes the URL, which
     * busts both caches at once and needs no migration.
     *
     * <p>The trade-off is that superseded avatars are left in the bucket instead
     * of being overwritten; each upload adds one small object. Deleting the
     * previous object after a successful save would remove that, which needs a
     * storage DELETE call that does not exist yet.
     */
    public static String avatarPathFor(String userId) {
        // UUID, not a timestamp: two uploads inside the same millisecond would
        // otherwise collide and silently reintroduce the stale-image bug.
        return avatarPathFor(userId, UUID.randomUUID().toString());
    }

    /** Deterministic overload so the versioning scheme is directly testable. */
    public static String avatarPathFor(String userId, String version) {
        return userId + "/" + AVATAR_FILE_PREFIX + version + ".jpg";
    }
}
