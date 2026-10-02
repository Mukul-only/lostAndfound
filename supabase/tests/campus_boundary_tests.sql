-- ====================================================================
-- SUPABASE TEST SUITE: NIT TRICHY CAMPUS BOUNDARY VALIDATION
-- ====================================================================

BEGIN;

-- Setup test users
INSERT INTO auth.users (id, email)
VALUES 
    ('11111111-1111-1111-1111-111111111111', 'alice@campus.edu'),
    ('22222222-2222-2222-2222-222222222222', 'bob@campus.edu'),
    ('33333333-3333-3333-3333-333333333333', 'eve@campus.edu')
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.profiles (id, full_name)
VALUES 
    ('11111111-1111-1111-1111-111111111111', 'Alice Finder'),
    ('22222222-2222-2222-2222-222222222222', 'Bob Claimant'),
    ('33333333-3333-3333-3333-333333333333', 'Eve Impostor')
ON CONFLICT (id) DO UPDATE SET full_name = EXCLUDED.full_name;

-- 1. Test is_point_in_nit_trichy_campus
DO $$
BEGIN
    -- Valid campus center
    IF NOT public.is_point_in_nit_trichy_campus(10.763385, 78.815029) THEN
        RAISE EXCEPTION 'TEST FAILED: Campus center should be inside';
    END IF;

    -- Valid admin quad
    IF NOT public.is_point_in_nit_trichy_campus(10.7589, 78.8132) THEN
        RAISE EXCEPTION 'TEST FAILED: Admin quad should be inside';
    END IF;

    -- Valid hostel zone
    IF NOT public.is_point_in_nit_trichy_campus(10.7610, 78.8220) THEN
        RAISE EXCEPTION 'TEST FAILED: Hostel zone should be inside';
    END IF;

    -- Outside North (Tanjore Rd)
    IF public.is_point_in_nit_trichy_campus(10.7760, 78.8150) THEN
        RAISE EXCEPTION 'TEST FAILED: North outside should be rejected';
    END IF;

    -- Outside South (BHEL)
    IF public.is_point_in_nit_trichy_campus(10.7500, 78.8150) THEN
        RAISE EXCEPTION 'TEST FAILED: South outside should be rejected';
    END IF;

    -- Outside West (Thuvakudi)
    IF public.is_point_in_nit_trichy_campus(10.7633, 78.8000) THEN
        RAISE EXCEPTION 'TEST FAILED: West outside should be rejected';
    END IF;

    -- Outside East
    IF public.is_point_in_nit_trichy_campus(10.7633, 78.8300) THEN
        RAISE EXCEPTION 'TEST FAILED: East outside should be rejected';
    END IF;

    -- Distant city (Chennai)
    IF public.is_point_in_nit_trichy_campus(13.0827, 80.2707) THEN
        RAISE EXCEPTION 'TEST FAILED: Chennai should be rejected';
    END IF;

    -- Nulls
    IF public.is_point_in_nit_trichy_campus(NULL, 78.8150) THEN
        RAISE EXCEPTION 'TEST FAILED: Null lat should be rejected';
    END IF;
    IF public.is_point_in_nit_trichy_campus(10.7633, NULL) THEN
        RAISE EXCEPTION 'TEST FAILED: Null lng should be rejected';
    END IF;

    RAISE NOTICE ' [PASS] is_point_in_nit_trichy_campus geometric boundary checks passed';
END $$;

-- 2. Test create_report_with_private_details out-of-campus coordinate rejection
DO $$
DECLARE
    v_threw BOOLEAN := false;
    v_res JSON;
BEGIN
    PERFORM set_config('request.jwt.claim.sub', '11111111-1111-1111-1111-111111111111', true);
    PERFORM set_config('request.jwt.claim.role', 'authenticated', true);

    BEGIN
        -- Named notation, not positional: migration 008 reordered this function's
        -- parameters, so a positional call only matches one of the signatures.
        v_res := public.create_report_with_private_details(
            p_type                         => 'FOUND',
            p_title                        => 'Found Calculator Out of Bounds',
            p_category                     => 'ELECTRONICS',
            p_description                  => 'Casio scientific calculator found near bus stand',
            p_public_verification_question => 'What model?',
            p_finder_private_notes         => 'Has sticker on back',
            p_image_url                    => NULL,
            p_campus_location              => 'Other',
            p_incident_date                => CURRENT_DATE,
            p_incident_time_approx         => '14:30',
            p_latitude                     => 13.0827,  -- Chennai: out of bounds
            p_longitude                    => 80.2707
        );
    EXCEPTION WHEN OTHERS THEN
        v_threw := true;
    END;

    IF NOT v_threw THEN
        RAISE EXCEPTION 'TEST FAILED: Out of bounds report coordinates were not rejected';
    END IF;

    RAISE NOTICE ' [PASS] Out-of-bounds report coordinate rejection verified';
END $$;

-- 3. Test create_report_with_private_details inside NIT Trichy coordinates success
DO $$
DECLARE
    v_res JSON;
BEGIN
    PERFORM set_config('request.jwt.claim.sub', '11111111-1111-1111-1111-111111111111', true);
    PERFORM set_config('request.jwt.claim.role', 'authenticated', true);

    v_res := public.create_report_with_private_details(
        p_type                         => 'FOUND',
        p_title                        => 'Found ID Card inside NIT Trichy',
        p_category                     => 'CARDS_ID',
        p_description                  => 'Found student ID near admin quad',
        p_public_verification_question => 'What is the roll number prefix?',
        p_finder_private_notes         => 'Roll starts with 1061',
        p_image_url                    => NULL,
        p_campus_location              => 'Engineering Quad',
        p_incident_date                => CURRENT_DATE,
        p_incident_time_approx         => '10:15',
        p_latitude                     => 10.763385,  -- NIT Trichy campus centre
        p_longitude                    => 78.815029
    );

    IF v_res IS NULL OR (v_res->>'success')::boolean <> true THEN
        RAISE EXCEPTION 'TEST FAILED: Valid NIT Trichy coordinates report creation failed';
    END IF;

    RAISE NOTICE ' [PASS] Inside NIT Trichy report creation verified';
END $$;

DO $$
BEGIN
    RAISE NOTICE '==================================================';
    RAISE NOTICE 'ALL NIT TRICHY CAMPUS BOUNDARY TESTS PASSED!';
    RAISE NOTICE '==================================================';
END $$;

ROLLBACK;
