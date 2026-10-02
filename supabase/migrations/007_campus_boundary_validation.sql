-- ====================================================================
-- SUPABASE MIGRATION 007: NIT TRICHY CAMPUS BOUNDARY VALIDATION
-- ====================================================================
-- Authoritative campus boundary definition:
-- Source: OpenStreetMap (OSM) Way 100857261 ("National Institute of Technology Trichy",
--         Thuvakudi, Tiruchirappalli, Tamil Nadu 620015, India)
-- Bounding Box: Latitude [10.7517140, 10.7751656], Longitude [78.8041608, 78.8266969]
-- Enforces that all new report pins and chat meeting pins must be situated
-- strictly within the campus perimeter. Existing rows outside boundary remain readable.

-- 1. Function to validate coordinates against the NIT Trichy campus boundary (Ray-Casting Algorithm)
CREATE OR REPLACE FUNCTION public.is_point_in_nit_trichy_campus(
    p_lat DOUBLE PRECISION,
    p_lng DOUBLE PRECISION
)
RETURNS BOOLEAN AS $$
DECLARE
    v_lats CONSTANT DOUBLE PRECISION[] := ARRAY[
        10.7643598, 10.7636660, 10.7612979, 10.7600133, 10.7589581, 10.7580787,
        10.7575679, 10.7571655, 10.7568727, 10.7555905, 10.7547261, 10.7540632,
        10.7517140, 10.7559094, 10.7629959, 10.7631047, 10.7675741, 10.7729137,
        10.7731079, 10.7749224, 10.7749328, 10.7751656, 10.7701651, 10.7686944,
        10.7687813, 10.7689078, 10.7686654, 10.7684862, 10.7678960, 10.7667155,
        10.7674164, 10.7669316, 10.7671529, 10.7664256, 10.7665100, 10.7663202,
        10.7643598
    ];
    v_lngs CONSTANT DOUBLE PRECISION[] := ARRAY[
        78.8041608, 78.8053598, 78.8073470, 78.8086388, 78.8100640, 78.8112225,
        78.8119629, 78.8127349, 78.8132290, 78.8156994, 78.8173996, 78.8188680,
        78.8260312, 78.8266969, 78.8246711, 78.8246130, 78.8222254, 78.8204561,
        78.8179952, 78.8169095, 78.8167500, 78.8138687, 78.8123159, 78.8122892,
        78.8118212, 78.8114457, 78.8114457, 78.8113062, 78.8117032, 78.8119392,
        78.8084523, 78.8083021, 78.8076369, 78.8073043, 78.8066606, 78.8066392,
        78.8041608
    ];
    v_n INTEGER;
    v_i INTEGER;
    v_j INTEGER;
    v_lati DOUBLE PRECISION;
    v_lngi DOUBLE PRECISION;
    v_latj DOUBLE PRECISION;
    v_lngj DOUBLE PRECISION;
    v_intersect_lng DOUBLE PRECISION;
    v_inside BOOLEAN := false;
BEGIN
    IF p_lat IS NULL OR p_lng IS NULL THEN
        RETURN false;
    END IF;

    -- Rapid rejection via bounding box
    IF p_lat < 10.7517140 OR p_lat > 10.7751656 OR p_lng < 78.8041608 OR p_lng > 78.8266969 THEN
        RETURN false;
    END IF;

    v_n := array_length(v_lats, 1);
    v_j := v_n;

    FOR v_i IN 1..v_n LOOP
        v_lati := v_lats[v_i];
        v_lngi := v_lngs[v_i];
        v_latj := v_lats[v_j];
        v_lngj := v_lngs[v_j];

        IF (v_lati > p_lat) <> (v_latj > p_lat) THEN
            v_intersect_lng := v_lngi + (p_lat - v_lati) * (v_lngj - v_lngi) / (v_latj - v_lati);
            IF p_lng < v_intersect_lng THEN
                v_inside := NOT v_inside;
            END IF;
        END IF;
        v_j := v_i;
    END LOOP;

    RETURN v_inside;
END;
$$ LANGUAGE plpgsql IMMUTABLE STRICT;

COMMENT ON FUNCTION public.is_point_in_nit_trichy_campus(DOUBLE PRECISION, DOUBLE PRECISION) IS
'Returns true if coordinates fall inside the NIT Trichy campus polygon (OSM way 100857261).';

GRANT EXECUTE ON FUNCTION public.is_point_in_nit_trichy_campus(DOUBLE PRECISION, DOUBLE PRECISION) TO authenticated;
GRANT EXECUTE ON FUNCTION public.is_point_in_nit_trichy_campus(DOUBLE PRECISION, DOUBLE PRECISION) TO anon;


-- 2. Update create_report_with_private_details to enforce campus boundary when coordinates are provided
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

REVOKE EXECUTE ON FUNCTION public.create_report_with_private_details(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, DATE, TEXT, DOUBLE PRECISION, DOUBLE PRECISION) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.create_report_with_private_details(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, DATE, TEXT, DOUBLE PRECISION, DOUBLE PRECISION) TO authenticated;


-- 3. Update propose_meeting_location to enforce campus boundary
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

    -- Enforce NIT Trichy campus boundary
    IF NOT public.is_point_in_nit_trichy_campus(p_latitude, p_longitude) THEN
        RAISE EXCEPTION 'Proposed meeting location coordinates are outside the NIT Trichy campus boundary. Pins must be placed within campus.';
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

REVOKE EXECUTE ON FUNCTION public.propose_meeting_location(UUID, DOUBLE PRECISION, DOUBLE PRECISION, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.propose_meeting_location(UUID, DOUBLE PRECISION, DOUBLE PRECISION, TEXT) TO authenticated;
