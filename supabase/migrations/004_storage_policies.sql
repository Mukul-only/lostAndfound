-- ====================================================================
-- SUPABASE MIGRATION 004: STORAGE BUCKET & ACCESS POLICIES
-- ====================================================================

-- WARNING TO SYSTEM ADMINISTRATORS AND STUDENTS:
-- The 'report-photos' bucket is configured for PUBLIC READ access so that item
-- photos can be displayed in the campus Lost & Found catalog without signed URL overhead.
-- DO NOT upload private verification evidence, government IDs, student ID cards,
-- dorm keys with room numbers, or secret identifying marks to this bucket.
-- Private verification details are textual and secured in PostgreSQL tables.

-- 1. Create the bucket if it does not already exist
INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES (
    'report-photos',
    'report-photos',
    true,
    5242880, -- 5 MB limit
    ARRAY['image/jpeg', 'image/png', 'image/webp']
)
ON CONFLICT (id) DO UPDATE SET
    public = true,
    file_size_limit = 5242880,
    allowed_mime_types = ARRAY['image/jpeg', 'image/png', 'image/webp'];

-- 2. Storage RLS Policies on storage.objects

-- Allow anyone to view report photos
DROP POLICY IF EXISTS "Report photos are publicly readable" ON storage.objects;
CREATE POLICY "Report photos are publicly readable"
ON storage.objects FOR SELECT
USING (bucket_id = 'report-photos');

-- Allow authenticated students to upload photos only into their own user folder: <user_id>/<filename>
DROP POLICY IF EXISTS "Students can upload report photos to their folder" ON storage.objects;
CREATE POLICY "Students can upload report photos to their folder"
ON storage.objects FOR INSERT TO authenticated
WITH CHECK (
    bucket_id = 'report-photos'
    AND (storage.foldername(name))[1] = auth.uid()::text
);

-- Allow students to update/overwrite photos in their own folder
DROP POLICY IF EXISTS "Students can update their own report photos" ON storage.objects;
CREATE POLICY "Students can update their own report photos"
ON storage.objects FOR UPDATE TO authenticated
USING (
    bucket_id = 'report-photos'
    AND (storage.foldername(name))[1] = auth.uid()::text
);

-- Allow students to delete photos in their own folder
DROP POLICY IF EXISTS "Students can delete their own report photos" ON storage.objects;
CREATE POLICY "Students can delete their own report photos"
ON storage.objects FOR DELETE TO authenticated
USING (
    bucket_id = 'report-photos'
    AND (storage.foldername(name))[1] = auth.uid()::text
);
