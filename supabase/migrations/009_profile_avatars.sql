-- ====================================================================
-- SUPABASE MIGRATION 009: PROFILE AVATARS (COLUMN + STORAGE BUCKET)
-- ====================================================================
-- Adds an optional avatar reference to profiles and a dedicated storage
-- bucket for profile photos.
--
-- Access decision: avatars are PUBLICLY READABLE (same posture as the
-- report-photos bucket) so profile pictures render in Settings, the home
-- header, and other campus-visible surfaces without signed-URL overhead.
-- Do NOT upload sensitive documents here; only profile photos.
-- Upload/replace/delete is restricted to the signed-in user's own folder
-- (<user_id>/...), enforced by storage RLS below. Profile row ownership
-- is already enforced by the "Users can update their own profile" policy
-- (migration 003); no profile table policy change is needed.
--
-- The Android app always uploads to the fixed path <uid>/avatar.jpg with
-- upsert, so replacing a photo overwrites in place and never orphans files.

-- 1. Avatar reference on profiles (storage path, e.g. "<uid>/avatar.jpg")
ALTER TABLE public.profiles
ADD COLUMN IF NOT EXISTS avatar_url TEXT;

-- 2. Create the avatars bucket if it does not already exist
INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES (
    'avatars',
    'avatars',
    true,
    2097152, -- 2 MB limit (app compresses to ~100 KB before upload)
    ARRAY['image/jpeg', 'image/png', 'image/webp']
)
ON CONFLICT (id) DO UPDATE SET
    public = true,
    file_size_limit = 2097152,
    allowed_mime_types = ARRAY['image/jpeg', 'image/png', 'image/webp'];

-- 3. Storage RLS policies on storage.objects for the avatars bucket

-- Anyone can view avatars
DROP POLICY IF EXISTS "Avatars are publicly readable" ON storage.objects;
CREATE POLICY "Avatars are publicly readable"
ON storage.objects FOR SELECT
USING (bucket_id = 'avatars');

-- Authenticated users may upload only into their own user folder
DROP POLICY IF EXISTS "Users can upload their own avatar" ON storage.objects;
CREATE POLICY "Users can upload their own avatar"
ON storage.objects FOR INSERT TO authenticated
WITH CHECK (
    bucket_id = 'avatars'
    AND (storage.foldername(name))[1] = auth.uid()::text
);

-- Users may overwrite their own avatar (replace in place)
DROP POLICY IF EXISTS "Users can update their own avatar" ON storage.objects;
CREATE POLICY "Users can update their own avatar"
ON storage.objects FOR UPDATE TO authenticated
USING (
    bucket_id = 'avatars'
    AND (storage.foldername(name))[1] = auth.uid()::text
);

-- Users may delete their own avatar
DROP POLICY IF EXISTS "Users can delete their own avatar" ON storage.objects;
CREATE POLICY "Users can delete their own avatar"
ON storage.objects FOR DELETE TO authenticated
USING (
    bucket_id = 'avatars'
    AND (storage.foldername(name))[1] = auth.uid()::text
);
