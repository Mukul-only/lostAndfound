package com.example.lostandfound.data.repository;

import android.content.Context;
import com.example.lostandfound.data.model.Profile;
import com.example.lostandfound.data.remote.SupabaseClient;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * In-memory directory of campus profiles used ONLY for display purposes
 * (names and avatars in feeds, claims, and chats).
 *
 * Design notes:
 * - Historical rows keep their stored name copies as an audit trail;
 *   screens prefer the live profile here and fall back to the stored copy,
 *   so a renamed user visibly updates everywhere without rewriting history.
 * - Ownership and permissions never consult this cache; they rely on
 *   server-side ids (owner_id / sender_id / RLS).
 * - Reads use the existing "Profiles readable by authenticated students"
 *   policy; no new data exposure is introduced.
 */
public class ProfileDirectory {
    public interface DirectoryCallback {
        void onDone();
    }

    private static ProfileDirectory instance;
    private final Map<String, Profile> cache = new HashMap<>();

    private ProfileDirectory() {}

    public static synchronized ProfileDirectory getInstance() {
        if (instance == null) {
            instance = new ProfileDirectory();
        }
        return instance;
    }

    /** PostgREST id filter for a batch of user ids, e.g. "in.(a,b)". */
    public static String idsFilter(Collection<String> ids) {
        if (ids == null) return "";
        List<String> clean = new ArrayList<>();
        for (String id : ids) {
            if (id != null && !id.trim().isEmpty() && !clean.contains(id.trim())) {
                clean.add(id.trim());
            }
        }
        if (clean.isEmpty()) return "";
        Collections.sort(clean);
        StringBuilder filter = new StringBuilder("in.(");
        for (int i = 0; i < clean.size(); i++) {
            if (i > 0) filter.append(',');
            filter.append(clean.get(i));
        }
        filter.append(')');
        return filter.toString();
    }

    /** Live display name, falling back to the stored copy when unknown. */
    public static String displayNameFor(Profile profile, String fallback) {
        if (profile != null && profile.getFullName() != null && !profile.getFullName().trim().isEmpty()) {
            return profile.getFullName();
        }
        return fallback != null ? fallback : "Campus Student";
    }

    /** Avatar storage path, or null when the user has no photo. */
    public static String avatarPathFor(Profile profile) {
        if (profile == null || profile.getAvatarUrl() == null) return null;
        String path = profile.getAvatarUrl().trim();
        return path.isEmpty() ? null : path;
    }

    /**
     * Fetches any uncached profiles for the given ids (single request) and
     * runs onDone in all cases. Callers refresh their adapter afterwards so
     * rows rebind with live names/avatars.
     */
    public synchronized void prefetch(Context context, Collection<String> ids, DirectoryCallback callback) {
        List<String> missing = new ArrayList<>();
        if (ids != null) {
            for (String id : ids) {
                if (id != null && !id.trim().isEmpty() && !cache.containsKey(id.trim())) {
                    missing.add(id.trim());
                }
            }
        }
        if (missing.isEmpty()) {
            if (callback != null) callback.onDone();
            return;
        }

        SupabaseClient.getInstance(context.getApplicationContext())
                .getRestService()
                .getProfiles("*", idsFilter(missing))
                .enqueue(new Callback<List<Profile>>() {
                    @Override
                    public void onResponse(Call<List<Profile>> call, Response<List<Profile>> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            synchronized (ProfileDirectory.this) {
                                for (Profile profile : response.body()) {
                                    if (profile != null && profile.getId() != null) {
                                        cache.put(profile.getId(), profile);
                                    }
                                }
                            }
                        }
                        if (callback != null) callback.onDone();
                    }

                    @Override
                    public void onFailure(Call<List<Profile>> call, Throwable t) {
                        if (callback != null) callback.onDone();
                    }
                });
    }

    public synchronized String getDisplayName(String userId, String fallback) {
        return displayNameFor(cache.get(userId), fallback);
    }

    public synchronized String getAvatarPath(String userId) {
        return avatarPathFor(cache.get(userId));
    }

    public synchronized void put(Profile profile) {
        if (profile != null && profile.getId() != null) {
            cache.put(profile.getId(), profile);
        }
    }

    /** Drops every cached profile. Must be called on sign-out so a different
        account never renders the previous user's names or avatars. */
    public synchronized void clear() {
        cache.clear();
    }

    public synchronized void clearForTesting() {
        clear();
    }
}
