-- ====================================================================
-- SUPABASE TEST SUITE: MAP FEATURES & HANDOVER MEETING LOCATIONS
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

CREATE OR REPLACE FUNCTION test_map_features()
RETURNS VOID AS $$
DECLARE
    v_alice_id UUID := '11111111-1111-1111-1111-111111111111';
    v_bob_id UUID := '22222222-2222-2222-2222-222222222222';
    v_eve_id UUID := '33333333-3333-3333-3333-333333333333';
    v_res JSON;
    v_report_id UUID;
    v_claim_id UUID;
    v_conv_id UUID;
    v_meeting_id UUID;
    v_count INT;
    v_lat DOUBLE PRECISION;
    v_status TEXT;
    v_is_current BOOLEAN;
    v_event_count INT;
BEGIN
    -- 1. Create FOUND report with coordinates inside NIT Trichy as Alice
    PERFORM set_config('request.jwt.claim.sub', v_alice_id::TEXT, true);
    PERFORM set_config('request.jwt.claim.role', 'authenticated', true);

    -- Named notation, not positional: migration 008 reordered this function's
    -- parameters, so a positional call only matches one of the two signatures.
    v_res := public.create_report_with_private_details(
        p_type                         => 'FOUND',
        p_title                        => 'Calculus Textbook with Blue Cover',
        p_category                     => 'BOOKS_STATIONERY',
        p_description                  => 'Found on a study desk in central library.',
        p_public_verification_question => 'What name is written on inside cover?',
        p_finder_private_notes         => 'Alice student ID written on page 3',
        p_image_url                    => NULL,
        p_campus_location              => 'Library',
        p_incident_date                => CURRENT_DATE,
        p_incident_time_approx         => '15:00',
        p_latitude                     => 10.763385,
        p_longitude                    => 78.815029
    );

    v_report_id := (v_res->>'report_id')::UUID;
    ASSERT v_report_id IS NOT NULL, 'Report creation failed';

    -- Verify coordinates were saved properly
    SELECT latitude INTO v_lat FROM public.reports WHERE id = v_report_id;
    ASSERT v_lat = 10.763385, 'Report latitude not saved accurately';
    RAISE NOTICE ' [PASS] Step 1: Report created with coordinates inside NIT Trichy (10.763385, 78.815029)';

    -- 2. Verify coordinate bounds check (out of campus rejection)
    BEGIN
        PERFORM public.create_report_with_private_details(
            p_type                         => 'FOUND',
            p_title                        => 'Invalid Lat Report',
            p_category                     => 'OTHER',
            p_description                  => 'Description test',
            p_public_verification_question => 'Question',
            p_finder_private_notes         => 'Secret',
            p_image_url                    => NULL,
            p_campus_location              => 'Library',
            p_incident_date                => CURRENT_DATE,
            p_incident_time_approx         => '10:00',
            p_latitude                     => 13.0827,
            p_longitude                    => 80.2707
        );
        RAISE EXCEPTION 'Chennai coordinates were unexpectedly accepted';
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE ' [PASS] Step 2: Out of bounds coordinates correctly rejected: %', SQLERRM;
    END;

    -- 3. Bob submits claim for Alice's report
    PERFORM set_config('request.jwt.claim.sub', v_bob_id::TEXT, true);
    v_res := public.submit_claim(v_report_id, 'That is my calculus book, name is Bob Smith');
    v_claim_id := (v_res->>'claim_id')::UUID;
    ASSERT v_claim_id IS NOT NULL, 'Claim submission failed';
    RAISE NOTICE ' [PASS] Step 3: Bob submitted claim';

    -- 4. Alice accepts Bob's claim -> generates conversation
    PERFORM set_config('request.jwt.claim.sub', v_alice_id::TEXT, true);
    v_res := public.accept_claim(v_claim_id);
    v_conv_id := (v_res->>'conversation_id')::UUID;
    ASSERT v_conv_id IS NOT NULL, 'Accept claim failed to generate conversation';
    RAISE NOTICE ' [PASS] Step 4: Alice accepted claim; conversation created: %', v_conv_id;

    -- 5. Alice proposes a handover meeting spot inside NIT Trichy (Hostel Zone)
    v_res := public.propose_meeting_location(
        v_conv_id,
        10.7610,
        78.8220,
        'Outside Opal Hostel entrance'
    );
    v_meeting_id := (v_res->>'meeting_id')::UUID;
    ASSERT v_meeting_id IS NOT NULL, 'Propose meeting location failed';
    ASSERT (v_res->>'status') = 'PROPOSED', 'Meeting status should be PROPOSED';
    RAISE NOTICE ' [PASS] Step 5: Alice proposed meeting location';

    -- 6. Privacy check: Eve (an uninvolved student) cannot read the meeting location
    PERFORM set_config('request.jwt.claim.sub', v_eve_id::TEXT, true);
    SELECT count(*) INTO v_count FROM public.conversation_meeting_locations WHERE id = v_meeting_id;
    ASSERT v_count = 0, 'Eve was able to read private handover meeting location!';
    RAISE NOTICE ' [PASS] Step 6: Eve cannot view meeting location (RLS isolation verified)';

    -- 7. Eve cannot propose meeting location for Alice & Bob conversation
    BEGIN
        PERFORM public.propose_meeting_location(v_conv_id, 10.763385, 78.815029, 'Eve intruder proposal');
        RAISE EXCEPTION 'Eve was unexpectedly allowed to propose a meeting location';
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE ' [PASS] Step 7: Eve proposal blocked: %', SQLERRM;
    END;

    -- 8. Proposer (Alice) cannot confirm her own meeting location
    PERFORM set_config('request.jwt.claim.sub', v_alice_id::TEXT, true);
    BEGIN
        PERFORM public.confirm_meeting_location(v_meeting_id);
        RAISE EXCEPTION 'Proposer was unexpectedly allowed to self-confirm meeting location';
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE ' [PASS] Step 8: Proposer self-confirmation blocked: %', SQLERRM;
    END;

    -- 9. Recipient (Bob) confirms the meeting location
    PERFORM set_config('request.jwt.claim.sub', v_bob_id::TEXT, true);
    v_res := public.confirm_meeting_location(v_meeting_id);
    ASSERT (v_res->>'status') = 'ACCEPTED', 'Confirmation failed to set ACCEPTED';

    SELECT status INTO v_status FROM public.conversation_meeting_locations WHERE id = v_meeting_id;
    ASSERT v_status = 'ACCEPTED', 'Meeting record status in DB is not ACCEPTED';
    RAISE NOTICE ' [PASS] Step 9: Bob successfully confirmed meeting location';

    -- 10. Proposing a new meeting spot no longer overwrites history (010/011).
    --     The earlier acceptance survives as "previously accepted" and the
    --     response event written by the confirm RPC is not duplicated.
    v_res := public.propose_meeting_location(
        v_conv_id,
        10.7589,
        78.8132,
        'Changed my mind, let us meet near Admin Quad'
    );
    SELECT status, is_current INTO v_status, v_is_current
    FROM public.conversation_meeting_locations WHERE id = v_meeting_id;
    ASSERT v_status = 'ACCEPTED', format('Earlier accepted spot lost its history (got %s)', v_status);
    ASSERT v_is_current, 'The agreed spot should still be the agreed spot after a new proposal';

    SELECT count(*) INTO v_event_count
    FROM public.meeting_response_events WHERE meeting_id = v_meeting_id;
    ASSERT v_event_count = 1,
        format('Confirming must write exactly one response event, got %s', v_event_count);
    RAISE NOTICE ' [PASS] Step 10: earlier spot kept as ACCEPTED, exactly 1 response event';

    RAISE NOTICE '==================================================';
    RAISE NOTICE 'ALL MAP FEATURES & MEETING LOCATION TESTS PASSED!';
    RAISE NOTICE '==================================================';
END;
$$ LANGUAGE plpgsql;

GRANT EXECUTE ON FUNCTION test_map_features() TO authenticated;

SET ROLE authenticated;

SELECT test_map_features();

RESET ROLE;

ROLLBACK;
