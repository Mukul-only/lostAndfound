-- ====================================================================
-- SUPABASE SECURITY TEST SUITE: MULTI-STUDENT ISOLATION & INTEGRITY
-- ====================================================================
-- Run this test script in the Supabase SQL Editor.
-- It executes inside a transaction that ROLLS BACK at the end so it leaves no test data behind.

BEGIN;

-- Helper notice function
CREATE OR REPLACE FUNCTION test_log(step_num INT, test_name TEXT, passed BOOLEAN, detail TEXT)
RETURNS VOID AS $$
BEGIN
    IF passed THEN
        RAISE NOTICE ' [PASS] Step %: % - %', step_num, test_name, detail;
    ELSE
        RAISE EXCEPTION '❌ [FAIL] Step %: % - %', step_num, test_name, detail;
    END IF;
END;
$$ LANGUAGE plpgsql;

-- 1. SETUP TEST USERS
-- Create mock users in auth.users if not present
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

GRANT EXECUTE ON FUNCTION test_log(INT, TEXT, BOOLEAN, TEXT) TO authenticated;

-- Switch to the authenticated role so RLS is strictly enforced
SET ROLE authenticated;

DO $$
DECLARE
    v_report_id UUID;
    v_claim_id UUID;
    v_conv_id UUID;
    v_count INT;
    v_caught BOOLEAN;
    v_result JSON;
BEGIN
    -- ----------------------------------------------------------------
    -- STEP 1: ALICE (FINDER) CREATES FOUND REPORT WITH PRIVATE NOTE
    -- ----------------------------------------------------------------
    PERFORM set_config('request.jwt.claim.sub', '11111111-1111-1111-1111-111111111111', true);
    PERFORM set_config('request.jwt.claim.role', 'authenticated', true);

    -- Named notation, not positional: migration 008 reordered this function's
    -- parameters, so a positional call only matches one of the two signatures.
    v_result := public.create_report_with_private_details(
        p_type                         => 'FOUND',
        p_title                        => 'Blue Hydro Flask with Stickers',
        p_category                     => 'OTHER',
        p_description                  => 'Found near Science Hall room 204.',
        p_public_verification_question => 'What specific animal sticker is next to the logo?',
        p_finder_private_notes         => 'The sticker is an origami orange fox with initials AF',
        p_image_url                    => NULL,
        p_campus_location              => 'Science Hall',
        p_incident_date                => CURRENT_DATE,
        p_incident_time_approx         => 'Around 2:00 PM'
    );
    v_report_id := (v_result->>'report_id')::UUID;
    PERFORM test_log(1, 'Report Creation', v_report_id IS NOT NULL, 'Report created by Alice');

    -- ----------------------------------------------------------------
    -- STEP 2: BOB ATTEMPTS TO READ ALICE'S PRIVATE NOTE
    -- ----------------------------------------------------------------
    PERFORM set_config('request.jwt.claim.sub', '22222222-2222-2222-2222-222222222222', true);

    SELECT count(*) INTO v_count
    FROM public.report_private_details
    WHERE report_id = v_report_id;

    PERFORM test_log(2, 'RLS Private Note Isolation', v_count = 0, 'Bob read 0 private notes belonging to Alice');

    -- ----------------------------------------------------------------
    -- STEP 3: ALICE READS HER OWN PRIVATE NOTE
    -- ----------------------------------------------------------------
    PERFORM set_config('request.jwt.claim.sub', '11111111-1111-1111-1111-111111111111', true);

    SELECT count(*) INTO v_count
    FROM public.report_private_details
    WHERE report_id = v_report_id;

    PERFORM test_log(3, 'RLS Owner Private Note Access', v_count = 1, 'Alice successfully read her private note');

    -- ----------------------------------------------------------------
    -- STEP 4: BOB SUBMITS A VALID CLAIM WITH PRIVATE EVIDENCE
    -- ----------------------------------------------------------------
    PERFORM set_config('request.jwt.claim.sub', '22222222-2222-2222-2222-222222222222', true);

    v_result := public.submit_claim(
        v_report_id,
        'It has an origami orange fox sticker! I lost it after Chemistry lecture.'
    );
    v_claim_id := (v_result->>'claim_id')::UUID;
    PERFORM test_log(4, 'Claim Submission', v_claim_id IS NOT NULL, 'Bob submitted claim');

    -- ----------------------------------------------------------------
    -- STEP 5: BOB ATTEMPTS TO SUBMIT DUPLICATE CLAIM ON SAME REPORT
    -- ----------------------------------------------------------------
    v_caught := false;
    BEGIN
        PERFORM public.submit_claim(v_report_id, 'Duplicate attempt');
    EXCEPTION WHEN OTHERS THEN
        v_caught := true;
    END;
    PERFORM test_log(5, 'Prevent Duplicate Claim', v_caught, 'Duplicate claim correctly rejected with exception');

    -- ----------------------------------------------------------------
    -- STEP 6: ALICE ATTEMPTS TO CLAIM HER OWN REPORT
    -- ----------------------------------------------------------------
    PERFORM set_config('request.jwt.claim.sub', '11111111-1111-1111-1111-111111111111', true);
    v_caught := false;
    BEGIN
        PERFORM public.submit_claim(v_report_id, 'Self claiming my own item');
    EXCEPTION WHEN OTHERS THEN
        v_caught := true;
    END;
    PERFORM test_log(6, 'Prevent Self-Claim', v_caught, 'Self-claim correctly rejected');

    -- ----------------------------------------------------------------
    -- STEP 7: EVE (THIRD PARTY) CANNOT READ BOB'S PRIVATE CLAIM
    -- ----------------------------------------------------------------
    PERFORM set_config('request.jwt.claim.sub', '33333333-3333-3333-3333-333333333333', true);

    SELECT count(*) INTO v_count
    FROM public.claims
    WHERE id = v_claim_id;

    PERFORM test_log(7, 'RLS Private Claim Isolation', v_count = 0, 'Eve read 0 claims for Bob/Alice report');

    -- ----------------------------------------------------------------
    -- STEP 8: EVE CANNOT ACCEPT BOB'S CLAIM (FORGERY ATTEMPT)
    -- ----------------------------------------------------------------
    v_caught := false;
    BEGIN
        PERFORM public.accept_claim(v_claim_id);
    EXCEPTION WHEN OTHERS THEN
        v_caught := true;
    END;
    PERFORM test_log(8, 'Prevent Unauthorized Claim Acceptance', v_caught, 'Eve acceptance attempt rejected');

    -- ----------------------------------------------------------------
    -- STEP 9: TAMPER TEST: BOB ATTEMPTS DIRECT UPDATE TO ALTER CLAIM STATUS
    --
    -- The assertion is the OUTCOME, not the presence of an exception. Which
    -- layer stops a direct UPDATE depends on the deployment: if authenticated
    -- holds no UPDATE privilege the statement raises "permission denied"; if it
    -- does hold one, the missing UPDATE policy plus FORCE ROW LEVEL SECURITY
    -- filters the row out and the statement quietly matches ZERO rows - and the
    -- protect_claim_fields trigger never even fires, because no row is reached.
    --
    -- So "no exception was raised" does NOT mean the tamper succeeded. The
    -- security property that actually matters is that the claim's status did not
    -- move, and that is what is checked here. This holds whichever layer is
    -- doing the work.
    -- ----------------------------------------------------------------
    PERFORM set_config('request.jwt.claim.sub', '22222222-2222-2222-2222-222222222222', true);
    v_caught := false;
    BEGIN
        UPDATE public.claims
        SET status = 'ACCEPTED'
        WHERE id = v_claim_id;
    EXCEPTION WHEN OTHERS THEN
        v_caught := true;
    END;

    -- The real assertion: the claim is still PENDING, i.e. Bob's direct write
    -- had no effect. If either layer let it through, this count is 0 and the
    -- step fails.
    SELECT count(*) INTO v_count
    FROM public.claims
    WHERE id = v_claim_id AND status = 'PENDING';
    PERFORM test_log(9, 'Prevent Direct Client Status Update on Claims',
                     v_count = 1,
                     CASE WHEN v_caught
                          THEN 'Direct status update rejected by privileges; claim still PENDING'
                          ELSE 'Direct status update blocked by RLS; claim still PENDING'
                     END);

    -- ----------------------------------------------------------------
    -- STEP 10: ALICE (OWNER) ACCEPTS BOB'S CLAIM VIA ATOMIC RPC
    -- ----------------------------------------------------------------
    PERFORM set_config('request.jwt.claim.sub', '11111111-1111-1111-1111-111111111111', true);
    v_result := public.accept_claim(v_claim_id);
    v_conv_id := (v_result->>'conversation_id')::UUID;
    PERFORM test_log(10, 'Atomic Claim Acceptance', v_conv_id IS NOT NULL, 'Alice accepted claim; conversation created');

    -- Verify report status is now HANDOVER_ARRANGED
    SELECT count(*) INTO v_count
    FROM public.reports
    WHERE id = v_report_id AND status = 'HANDOVER_ARRANGED' AND accepted_claim_id = v_claim_id;
    PERFORM test_log(10, 'Report State Transition', v_count = 1, 'Report is HANDOVER_ARRANGED with accepted_claim_id set');

    -- ----------------------------------------------------------------
    -- STEP 11: EVE ATTEMPTS TO POST MESSAGE IN ALICE & BOB CONVERSATION
    -- ----------------------------------------------------------------
    PERFORM set_config('request.jwt.claim.sub', '33333333-3333-3333-3333-333333333333', true);
    v_caught := false;
    BEGIN
        INSERT INTO public.messages (conversation_id, sender_id, sender_name, content)
        VALUES (v_conv_id, '33333333-3333-3333-3333-333333333333', 'Eve', 'Intruder message');
    EXCEPTION WHEN OTHERS THEN
        v_caught := true;
    END;
    PERFORM test_log(11, 'RLS Conversation Message Injection', v_caught, 'Eve message insertion blocked by policy');

    -- ----------------------------------------------------------------
    -- STEP 12: BOB CAN POST AND ALICE CAN READ MESSAGES
    -- ----------------------------------------------------------------
    PERFORM set_config('request.jwt.claim.sub', '22222222-2222-2222-2222-222222222222', true);
    INSERT INTO public.messages (conversation_id, sender_id, sender_name, content)
    VALUES (v_conv_id, '22222222-2222-2222-2222-222222222222', 'Bob', 'Hi Alice! Can we meet at the campus library desk?');

    PERFORM set_config('request.jwt.claim.sub', '11111111-1111-1111-1111-111111111111', true);
    SELECT count(*) INTO v_count
    FROM public.messages
    WHERE conversation_id = v_conv_id;
    PERFORM test_log(12, 'Legitimate Messaging', v_count = 1, 'Bob sent and Alice read the message');

    -- ----------------------------------------------------------------
    -- STEP 13: EVE TRIES TO CLAIM REPORT AFTER STATUS CHANGED (NOT OPEN)
    -- ----------------------------------------------------------------
    PERFORM set_config('request.jwt.claim.sub', '33333333-3333-3333-3333-333333333333', true);
    v_caught := false;
    BEGIN
        PERFORM public.submit_claim(v_report_id, 'Late claim attempt by Eve');
    EXCEPTION WHEN OTHERS THEN
        v_caught := true;
    END;
    PERFORM test_log(13, 'Prevent Claim on Non-Open Report', v_caught, 'Claim on HANDOVER_ARRANGED report blocked');

    -- ----------------------------------------------------------------
    -- STEP 14: ALICE CREATES REPORT 2, BOB CLAIMS, ALICE REJECTS WITH OPTIONAL MESSAGE
    -- ----------------------------------------------------------------
    PERFORM set_config('request.jwt.claim.sub', '11111111-1111-1111-1111-111111111111', true);
    v_result := public.create_report_with_private_details(
        p_type                         => 'FOUND',
        p_title                        => 'Graphing Calculator TI-84 Plus',
        p_category                     => 'ELECTRONICS',
        p_description                  => 'Found near Science Building lecture hall 101',
        p_public_verification_question => 'What stickers or engravings are on the back casing?',
        p_finder_private_notes         => 'Serial ending in 9821, yellow star sticker',
        p_image_url                    => NULL,
        p_campus_location              => 'Science Building Room 101',
        p_incident_date                => '2026-09-28'::DATE,
        p_incident_time_approx         => 'Morning'
    );
    v_report_id := (v_result->>'report_id')::UUID;

    -- Bob submits claim on Report 2
    PERFORM set_config('request.jwt.claim.sub', '22222222-2222-2222-2222-222222222222', true);
    v_result := public.submit_claim(v_report_id, 'I think that is my TI-84 with Batman sticker on back');
    v_claim_id := (v_result->>'claim_id')::UUID;

    -- Alice rejects Bob's claim with message
    PERFORM set_config('request.jwt.claim.sub', '11111111-1111-1111-1111-111111111111', true);
    v_result := public.reject_claim(v_claim_id, 'Stickers do not match; the calculator found has a yellow star sticker.');
    PERFORM test_log(14, 'Owner Claim Rejection With Message', (v_result->>'success')::BOOLEAN, 'Alice rejected Bob claim with explanatory note');

    -- ----------------------------------------------------------------
    -- STEP 15: BOB CAN VIEW HIS REJECTED CLAIM AND THE REJECTION MESSAGE
    -- ----------------------------------------------------------------
    PERFORM set_config('request.jwt.claim.sub', '22222222-2222-2222-2222-222222222222', true);
    SELECT count(*) INTO v_count
    FROM public.claims
    WHERE id = v_claim_id
      AND status = 'REJECTED'
      AND rejection_message = 'Stickers do not match; the calculator found has a yellow star sticker.';
    PERFORM test_log(15, 'Claimant Sees Rejection and Note', v_count = 1, 'Bob safely fetched rejected claim and rejection note');

    -- ----------------------------------------------------------------
    -- STEP 16: EVE CANNOT SEE BOB REJECTED CLAIM OR REJECTION MESSAGE
    -- ----------------------------------------------------------------
    PERFORM set_config('request.jwt.claim.sub', '33333333-3333-3333-3333-333333333333', true);
    SELECT count(*) INTO v_count
    FROM public.claims
    WHERE report_id = v_report_id;
    PERFORM test_log(16, 'Third-Party Privacy for Rejection', v_count = 0, 'Eve cannot see Bob rejection or rejection note');

    -- ----------------------------------------------------------------
    -- STEP 17: BOB CANNOT SUBMIT A SECOND CLAIM (ENFORCED AT DB LEVEL)
    -- ----------------------------------------------------------------
    PERFORM set_config('request.jwt.claim.sub', '22222222-2222-2222-2222-222222222222', true);
    v_caught := false;
    BEGIN
        PERFORM public.submit_claim(v_report_id, 'Retrying another claim on the same calculator');
    EXCEPTION WHEN OTHERS THEN
        v_caught := true;
    END;
    PERFORM test_log(17, 'Prevent Duplicate Claim Post-Rejection', v_caught, 'Database enforced single claim per claimant');

    -- ----------------------------------------------------------------
    -- STEP 18: CLIENT CANNOT DIRECTLY MODIFY REJECTION_MESSAGE
    --
    -- Same reasoning as step 9: the assertion is the OUTCOME, not whether an
    -- exception was raised. claims has no UPDATE policy and FORCE ROW LEVEL
    -- SECURITY is on, so this UPDATE filters the row out and matches ZERO rows
    -- without raising anything - and the protect trigger never fires because no
    -- row is reached. Only the unchanged message proves the tamper failed.
    -- ----------------------------------------------------------------
    v_caught := false;
    BEGIN
        UPDATE public.claims
        SET rejection_message = 'Tampered message by client'
        WHERE id = v_claim_id;
    EXCEPTION WHEN OTHERS THEN
        v_caught := true;
    END;

    -- The real assertion: the note Alice wrote is intact. If any layer had let
    -- the write through this count would be 0 and the step would fail.
    SELECT count(*) INTO v_count
    FROM public.claims
    WHERE id = v_claim_id
      AND rejection_message = 'Stickers do not match; the calculator found has a yellow star sticker.';
    PERFORM test_log(18, 'Prevent Direct Rejection Message Tampering',
                     v_count = 1,
                     CASE WHEN v_caught
                          THEN 'Direct update rejected by privileges; rejection note intact'
                          ELSE 'Direct update blocked by RLS; rejection note intact'
                     END);

    -- ----------------------------------------------------------------
    -- STEP 19: EVE (NON-OWNER) CANNOT REJECT A CLAIM
    -- ----------------------------------------------------------------
    PERFORM set_config('request.jwt.claim.sub', '33333333-3333-3333-3333-333333333333', true);
    v_caught := false;
    BEGIN
        PERFORM public.reject_claim(v_claim_id, 'Unauthorized rejection attempt');
    EXCEPTION WHEN OTHERS THEN
        v_caught := true;
    END;
    PERFORM test_log(19, 'Prevent Unauthorized Claim Rejection', v_caught, 'Non-owner rejection blocked by reject_claim RPC');

    RAISE NOTICE '==================================================';
    RAISE NOTICE 'ALL 19 SECURITY TESTS PASSED SUCCESSFULLY!';
    RAISE NOTICE '==================================================';
END $$;

RESET ROLE;

-- Clean up helper function and rollback all test data
DROP FUNCTION test_log(INT, TEXT, BOOLEAN, TEXT);
ROLLBACK;
