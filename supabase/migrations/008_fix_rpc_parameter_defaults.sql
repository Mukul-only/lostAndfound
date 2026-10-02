-- ====================================================================
-- SUPABASE MIGRATION 008: FIX RPC PARAMETER DEFAULTS FOR LOST REPORTS
-- ====================================================================
-- Problem: PostgREST cannot find create_report_with_private_details when
-- Android omits null-valued params (p_public_verification_question,
-- p_finder_private_notes) for LOST reports. Gson skips nulls by default.
--
-- Fix: Add DEFAULT NULL to the two FOUND-only params so the function
-- matches calls with only 10 provided params (LOST) or all 12 (FOUND).

-- Drop the 12-arg function (from migration 007) so we can recreate with defaults
DROP FUNCTION IF EXISTS public.create_report_with_private_details(
    TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, DATE, TEXT, DOUBLE PRECISION, DOUBLE PRECISION
);

-- Recreate with DEFAULT NULL on all optional params (must come after required params in PostgreSQL)
CREATE OR REPLACE FUNCTION public.create_report_with_private_details(
    -- Required params (no defaults)
    p_type TEXT,
    p_title TEXT,
    p_category TEXT,
    p_description TEXT,
    p_image_url TEXT,
    p_campus_location TEXT,
    p_incident_date DATE,
    p_incident_time_approx TEXT,
    -- Optional params (with defaults) - must come after all required params
    p_public_verification_question TEXT DEFAULT NULL,
    p_finder_private_notes TEXT DEFAULT NULL,
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

    -- Enforce NIT Trichy campus boundary if coordinates are provided
    IF p_latitude IS NOT NULL OR p_longitude IS NOT NULL THEN
        IF p_latitude IS NULL OR p_longitude IS NULL THEN
            RAISE EXCEPTION 'Both latitude and longitude must be provided together';
        END IF;
        IF NOT public.is_point_in_nit_trichy_campus(p_latitude, p_longitude) THEN
            RAISE EXCEPTION 'Selected coordinates are outside the NIT Trichy campus boundary. Pins must be placed within campus.';
        END IF;
    END IF;

    -- Insert public report with validated coordinates
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
REVOKE EXECUTE ON FUNCTION public.create_report_with_private_details(
    TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, DATE, TEXT, TEXT, TEXT, DOUBLE PRECISION, DOUBLE PRECISION
) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.create_report_with_private_details(
    TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, DATE, TEXT, TEXT, TEXT, DOUBLE PRECISION, DOUBLE PRECISION
) TO authenticated;