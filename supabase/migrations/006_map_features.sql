-- ====================================================================
-- SUPABASE MIGRATION 006: MAP FEATURES & HANDOVER MEETING LOCATIONS
-- ====================================================================

-- 1. Add coordinates to public.reports
ALTER TABLE public.reports
ADD COLUMN IF NOT EXISTS latitude DOUBLE PRECISION,
ADD COLUMN IF NOT EXISTS longitude DOUBLE PRECISION;

-- Coordinate range CHECK constraints
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_reports_latitude'
    ) THEN
        ALTER TABLE public.reports
        ADD CONSTRAINT chk_reports_latitude
        CHECK (latitude IS NULL OR (latitude >= -90.0 AND latitude <= 90.0));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_reports_longitude'
    ) THEN
        ALTER TABLE public.reports
        ADD CONSTRAINT chk_reports_longitude
        CHECK (longitude IS NULL OR (longitude >= -180.0 AND longitude <= 180.0));
    END IF;
END $$;


-- 2. Update create_report_with_private_details to accept optional coordinates
-- Drop old 10-argument signature so the new 12-argument signature with defaults handles both
DROP FUNCTION IF EXISTS public.create_report_with_private_details(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, DATE, TEXT);

CREATE OR REPLACE FUNCTION public.create_report_with_private_details(
    p_type TEXT,
    p_title TEXT,
    p_category TEXT,
    p_description TEXT,
    p_public_verification_question TEXT,
    p_finder_private_notes TEXT,
    p_image_url TEXT,
    p_campus_location TEXT,
    p_incident_date DATE,
    p_incident_time_approx TEXT,
    p_latitude DOUBLE PRECISION DEFAULT NULL,
    p_longitude DOUBLE PRECISION DEFAULT NULL
)
RETURNS JSON AS $$
DECLARE
    v_user_id UUID;
    v_user_name TEXT;
    v_report_id UUID;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    -- Fetch owner full_name from profiles
    SELECT full_name INTO v_user_name FROM public.profiles WHERE id = v_user_id;
    IF v_user_name IS NULL OR trim(v_user_name) = '' THEN
        v_user_name := 'Campus Student';
    END IF;

    -- Validate coordinates if provided
    IF p_latitude IS NOT NULL AND (p_latitude < -90.0 OR p_latitude > 90.0) THEN
        RAISE EXCEPTION 'Invalid latitude';
    END IF;
    IF p_longitude IS NOT NULL AND (p_longitude < -180.0 OR p_longitude > 180.0) THEN
        RAISE EXCEPTION 'Invalid longitude';
    END IF;

    -- Insert public report with coordinates
    INSERT INTO public.reports (
        type,
        title,
        category,
        description,
        public_verification_question,
        image_url,
        campus_location,
        incident_date,
        incident_time_approx,
        latitude,
        longitude,
        status,
        owner_id,
        owner_name
    ) VALUES (
        p_type,
        p_title,
        p_category,
        p_description,
        p_public_verification_question,
        p_image_url,
        p_campus_location,
        p_incident_date,
        p_incident_time_approx,
        p_latitude,
        p_longitude,
        'OPEN',
        v_user_id,
        v_user_name
    ) RETURNING id INTO v_report_id;

    -- Insert private notes atomically if provided for a FOUND item
    IF p_type = 'FOUND' AND p_finder_private_notes IS NOT NULL AND char_length(trim(p_finder_private_notes)) > 0 THEN
        INSERT INTO public.report_private_details (
            report_id,
            finder_private_notes
        ) VALUES (
            v_report_id,
            p_finder_private_notes
        );
    END IF;

    RETURN json_build_object(
        'success', true,
        'report_id', v_report_id
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

-- Revoke default public execution & grant to authenticated
REVOKE EXECUTE ON FUNCTION public.create_report_with_private_details(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, DATE, TEXT, DOUBLE PRECISION, DOUBLE PRECISION) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.create_report_with_private_details(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, DATE, TEXT, DOUBLE PRECISION, DOUBLE PRECISION) TO authenticated;


-- 3. Dedicated table for private handover meeting locations
CREATE TABLE IF NOT EXISTS public.conversation_meeting_locations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
    proposer_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    latitude DOUBLE PRECISION NOT NULL CHECK (latitude >= -90.0 AND latitude <= 90.0),
    longitude DOUBLE PRECISION NOT NULL CHECK (longitude >= -180.0 AND longitude <= 180.0),
    location_note TEXT CHECK (location_note IS NULL OR char_length(trim(location_note)) <= 250),
    status TEXT NOT NULL DEFAULT 'PROPOSED' CHECK (status IN ('PROPOSED', 'CONFIRMED', 'SUPERSEDED')),
    confirmed_by UUID REFERENCES auth.users(id),
    confirmed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now()),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now())
);

CREATE INDEX IF NOT EXISTS idx_meeting_locations_conv
ON public.conversation_meeting_locations(conversation_id, created_at DESC);

-- Enable RLS and force it
ALTER TABLE public.conversation_meeting_locations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.conversation_meeting_locations FORCE ROW LEVEL SECURITY;

REVOKE ALL ON public.conversation_meeting_locations FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON public.conversation_meeting_locations TO authenticated;

-- RLS Policies: strictly isolated to participants in that conversation
DROP POLICY IF EXISTS "Meeting locations viewable by conversation participants" ON public.conversation_meeting_locations;
CREATE POLICY "Meeting locations viewable by conversation participants"
ON public.conversation_meeting_locations FOR SELECT TO authenticated
USING (
    EXISTS (
        SELECT 1 FROM public.conversations c
        WHERE c.id = conversation_meeting_locations.conversation_id
          AND (c.owner_id = auth.uid() OR c.claimant_id = auth.uid())
    )
);

DROP POLICY IF EXISTS "Meeting locations insertable by conversation participants" ON public.conversation_meeting_locations;
CREATE POLICY "Meeting locations insertable by conversation participants"
ON public.conversation_meeting_locations FOR INSERT TO authenticated
WITH CHECK (
    proposer_id = auth.uid()
    AND EXISTS (
        SELECT 1 FROM public.conversations c
        WHERE c.id = conversation_meeting_locations.conversation_id
          AND (c.owner_id = auth.uid() OR c.claimant_id = auth.uid())
    )
);

DROP POLICY IF EXISTS "Meeting locations updatable by conversation participants" ON public.conversation_meeting_locations;
CREATE POLICY "Meeting locations updatable by conversation participants"
ON public.conversation_meeting_locations FOR UPDATE TO authenticated
USING (
    EXISTS (
        SELECT 1 FROM public.conversations c
        WHERE c.id = conversation_meeting_locations.conversation_id
          AND (c.owner_id = auth.uid() OR c.claimant_id = auth.uid())
    )
);


-- 4. Atomic RPC to propose a handover meeting location
CREATE OR REPLACE FUNCTION public.propose_meeting_location(
    p_conversation_id UUID,
    p_latitude DOUBLE PRECISION,
    p_longitude DOUBLE PRECISION,
    p_location_note TEXT DEFAULT NULL
)
RETURNS JSON AS $$
DECLARE
    v_user_id UUID;
    v_conv public.conversations%ROWTYPE;
    v_trimmed_note TEXT;
    v_new_id UUID;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    -- Verify conversation exists and user is participant
    SELECT * INTO v_conv FROM public.conversations WHERE id = p_conversation_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Conversation not found';
    END IF;

    IF v_conv.owner_id <> v_user_id AND v_conv.claimant_id <> v_user_id THEN
        RAISE EXCEPTION 'Only conversation participants can propose a meeting location';
    END IF;

    -- Validate coordinates
    IF p_latitude < -90.0 OR p_latitude > 90.0 THEN
        RAISE EXCEPTION 'Invalid latitude';
    END IF;
    IF p_longitude < -180.0 OR p_longitude > 180.0 THEN
        RAISE EXCEPTION 'Invalid longitude';
    END IF;

    -- Validate note
    IF p_location_note IS NOT NULL THEN
        v_trimmed_note := trim(p_location_note);
        IF char_length(v_trimmed_note) = 0 THEN
            v_trimmed_note := NULL;
        ELSIF char_length(v_trimmed_note) > 250 THEN
            RAISE EXCEPTION 'Location note cannot exceed 250 characters';
        END IF;
    ELSE
        v_trimmed_note := NULL;
    END IF;

    -- Mark previous proposals or confirmed meetings as SUPERSEDED
    UPDATE public.conversation_meeting_locations
    SET status = 'SUPERSEDED',
        updated_at = timezone('utc'::text, now())
    WHERE conversation_id = p_conversation_id
      AND status IN ('PROPOSED', 'CONFIRMED');

    -- Insert new proposed meeting location
    INSERT INTO public.conversation_meeting_locations (
        conversation_id,
        proposer_id,
        latitude,
        longitude,
        location_note,
        status,
        created_at,
        updated_at
    ) VALUES (
        p_conversation_id,
        v_user_id,
        p_latitude,
        p_longitude,
        v_trimmed_note,
        'PROPOSED',
        timezone('utc'::text, now()),
        timezone('utc'::text, now())
    ) RETURNING id INTO v_new_id;

    RETURN json_build_object(
        'success', true,
        'meeting_id', v_new_id,
        'status', 'PROPOSED'
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;


-- 5. Atomic RPC to confirm a proposed handover meeting location
CREATE OR REPLACE FUNCTION public.confirm_meeting_location(
    p_meeting_id UUID
)
RETURNS JSON AS $$
DECLARE
    v_user_id UUID;
    v_meeting public.conversation_meeting_locations%ROWTYPE;
    v_conv public.conversations%ROWTYPE;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    -- Find the meeting location
    SELECT * INTO v_meeting FROM public.conversation_meeting_locations WHERE id = p_meeting_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Meeting location not found';
    END IF;

    -- Fetch conversation
    SELECT * INTO v_conv FROM public.conversations WHERE id = v_meeting.conversation_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Conversation not found';
    END IF;

    -- Verify user is participant
    IF v_conv.owner_id <> v_user_id AND v_conv.claimant_id <> v_user_id THEN
        RAISE EXCEPTION 'Only conversation participants can confirm a meeting location';
    END IF;

    -- Ensure proposer is not the one confirming (recipient confirms!)
    IF v_meeting.proposer_id = v_user_id THEN
        RAISE EXCEPTION 'You cannot confirm your own proposed handover location. The other student must confirm it.';
    END IF;

    -- Ensure meeting is currently PROPOSED
    IF v_meeting.status <> 'PROPOSED' THEN
        RAISE EXCEPTION 'Only a proposed meeting location can be confirmed';
    END IF;

    -- Update to CONFIRMED
    UPDATE public.conversation_meeting_locations
    SET status = 'CONFIRMED',
        confirmed_by = v_user_id,
        confirmed_at = timezone('utc'::text, now()),
        updated_at = timezone('utc'::text, now())
    WHERE id = p_meeting_id;

    RETURN json_build_object(
        'success', true,
        'meeting_id', p_meeting_id,
        'status', 'CONFIRMED'
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

-- Revoke default public execution & grant to authenticated
REVOKE EXECUTE ON FUNCTION public.propose_meeting_location(UUID, DOUBLE PRECISION, DOUBLE PRECISION, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.propose_meeting_location(UUID, DOUBLE PRECISION, DOUBLE PRECISION, TEXT) TO authenticated;

REVOKE EXECUTE ON FUNCTION public.confirm_meeting_location(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.confirm_meeting_location(UUID) TO authenticated;
