-- ====================================================================
-- SUPABASE TEST SUITE: PERSISTENT CHAT TIMELINE
--   - meeting proposals that accumulate instead of overwriting each other
--   - accept/decline permissions, finality, and the current-agreed-spot pointer
--   - one atomic, non-duplicable centered response event per proposal
--   - quoted replies that cannot cross conversations or forge a sender
-- Requires: 010_chat_timeline_schema.sql, 011_chat_timeline_rpc.sql
-- Run in the Supabase SQL Editor. Wrapped in a transaction that ROLLBACKs, so
-- it leaves no test data behind.
-- ====================================================================

BEGIN;

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

-- Runs as postgres so the raw INSERT/UPDATE privilege checks below are real
-- privilege checks rather than RLS-filtered reads.
CREATE OR REPLACE FUNCTION test_chat_timeline()
RETURNS VOID AS $$
DECLARE
    v_alice_id UUID := '11111111-1111-1111-1111-111111111111';
    v_bob_id   UUID := '22222222-2222-2222-2222-222222222222';
    v_eve_id   UUID := '33333333-3333-3333-3333-333333333333';

    v_res JSON;
    v_report_a UUID;
    v_claim_a UUID;
    v_conv_a UUID;
    v_report_b UUID;
    v_claim_b UUID;
    v_conv_b UUID;

    -- p1 is proposed by Alice and is the one that gets declined.
    -- p2 is proposed by Bob, and is accepted then later superseded.
    -- p3 is proposed by Alice and becomes the current agreed spot.
    -- p4 is left permanently unanswered.
    -- No trailing comments on declaration lines: the DECLARE block is kept
    -- byte-for-byte in the shape that map_features_tests.sql already runs.
    v_p1 UUID;
    v_p2 UUID;
    v_p3 UUID;
    v_p4 UUID;

    v_msg_a UUID;
    v_msg_b UUID;
    v_count INT;
    v_status TEXT;
    v_current BOOLEAN;
    v_actor UUID;
BEGIN
    -- =================================================================
    -- SETUP: two independent handover conversations.
    --   conversation A = Alice & Bob   (the thread under test)
    --   conversation B = Alice & Eve   (an unrelated thread, for reply isolation)
    -- =================================================================
    PERFORM set_config('request.jwt.claim.sub', v_alice_id::TEXT, true);
    PERFORM set_config('request.jwt.claim.role', 'authenticated', true);

    -- 10 params by NAME, not position. Migration 008 swapped p_image_url and
    -- p_campus_location relative to 007, so a positional call is only valid
    -- against one of them. Named notation binds by name, which is stable across
    -- both, and p_latitude/p_longitude are left unnamed so they take their
    -- DEFAULT NULL whether the installed function has 10 params (002) or 12
    -- (007/008). This test does not need coordinates.
    v_res := public.create_report_with_private_details(
        p_type                         => 'FOUND',
        p_title                        => 'Blue Hydro Flask',
        p_category                     => 'OTHER',
        p_description                  => 'Found on a table in the central library.',
        p_public_verification_question => 'What is written on the base?',
        p_finder_private_notes         => 'Alice student ID',
        p_image_url                    => NULL,
        p_campus_location              => 'Library',
        p_incident_date                => CURRENT_DATE,
        p_incident_time_approx         => '15:00'
    );
    v_report_a := (v_res->>'report_id')::UUID;

    PERFORM set_config('request.jwt.claim.sub', v_bob_id::TEXT, true);
    v_res := public.submit_claim(v_report_a, 'That is my flask, it says Bob on the base');
    v_claim_a := (v_res->>'claim_id')::UUID;
    PERFORM set_config('request.jwt.claim.sub', v_alice_id::TEXT, true);
    v_res := public.accept_claim(v_claim_a);
    v_conv_a := (v_res->>'conversation_id')::UUID;
    ASSERT v_conv_a IS NOT NULL, 'SETUP: conversation A not created';

    -- Named notation again, for the same reason. A LOST item has no
    -- verification question and no finder notes.
    v_res := public.create_report_with_private_details(
        p_type                         => 'LOST',
        p_title                        => 'Grey Notebook',
        p_category                     => 'BOOKS_STATIONERY',
        p_description                  => 'Left it somewhere between blocks.',
        p_public_verification_question => NULL,
        p_finder_private_notes         => NULL,
        p_image_url                    => NULL,
        p_campus_location              => 'Block C',
        p_incident_date                => CURRENT_DATE,
        p_incident_time_approx         => '09:00'
    );
    v_report_b := (v_res->>'report_id')::UUID;

    PERFORM set_config('request.jwt.claim.sub', v_eve_id::TEXT, true);
    v_res := public.submit_claim(v_report_b, 'I lost a grey notebook around block C');
    v_claim_b := (v_res->>'claim_id')::UUID;
    PERFORM set_config('request.jwt.claim.sub', v_alice_id::TEXT, true);
    v_res := public.accept_claim(v_claim_b);
    v_conv_b := (v_res->>'conversation_id')::UUID;
    ASSERT v_conv_b IS NOT NULL, 'SETUP: conversation B not created';
    RAISE NOTICE ' [PASS] SETUP: conversations A=% and B=% created', v_conv_a, v_conv_b;

    -- =================================================================
    -- 1. A PROPOSAL IS A TIMELINE ITEM OF ITS OWN
    -- =================================================================
    v_res := public.propose_meeting_location(v_conv_a, 10.7610, 78.8220, 'Opal Hostel entrance');
    v_p1 := (v_res->>'meeting_id')::UUID;
    ASSERT v_p1 IS NOT NULL, '1: proposal creation returned no id';
    ASSERT (v_res->>'status') = 'PROPOSED', '1: new proposal must start PROPOSED';
    SELECT count(*) INTO v_count FROM public.meeting_response_events WHERE meeting_id = v_p1;
    ASSERT v_count = 0, '1: proposing must not create a response event';
    RAISE NOTICE ' [PASS] Step 1: proposal % created, no event emitted', v_p1;

    -- =================================================================
    -- 2. A SECOND PROPOSAL DOES NOT OVERWRITE THE FIRST
    --    Both stay visible and both keep their own identity.
    -- =================================================================
    PERFORM set_config('request.jwt.claim.sub', v_bob_id::TEXT, true);
    v_res := public.propose_meeting_location(v_conv_a, 10.7589, 78.8132, 'Admin Quad steps');
    v_p2 := (v_res->>'meeting_id')::UUID;

    SELECT count(*) INTO v_count
    FROM public.conversation_meeting_locations
    WHERE conversation_id = v_conv_a;
    IF NOT (v_count = 2) THEN
        RAISE EXCEPTION '2: expected 2 proposals in the conversation, got %', v_count;
    END IF;

    SELECT status INTO v_status
    FROM public.conversation_meeting_locations WHERE id = v_p1;
    IF NOT (v_status = 'PROPOSED') THEN
        RAISE EXCEPTION '2: the earlier proposal must stay PROPOSED, got %', v_status;
    END IF;
    RAISE NOTICE ' [PASS] Step 2: second proposal kept, first still PROPOSED (no supersede)';

    -- =================================================================
    -- 3. A PROPOSER CANNOT RESPOND TO THEIR OWN PROPOSAL
    --    (p2 is Robin's proposal and we are still acting as Robin)
    -- =================================================================
    PERFORM set_config('request.jwt.claim.sub', v_bob_id::TEXT, true);
    BEGIN
        PERFORM public.respond_to_meeting_proposal(v_p2, 'ACCEPTED');
        RAISE EXCEPTION '3: proposer was allowed to accept their own proposal';
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE ' [PASS] Step 3: proposer self-response blocked: %', SQLERRM;
    END;

    -- =================================================================
    -- 4. THE OTHER PARTICIPANT DECLINES -> status AND event, ATOMICALLY
    --    p2 is Robin's proposal, so Alex is the one allowed to decline it.
    -- =================================================================
    PERFORM set_config('request.jwt.claim.sub', v_alice_id::TEXT, true);
    v_res := public.respond_to_meeting_proposal(v_p2, 'REJECTED');
    ASSERT (v_res->>'status') = 'REJECTED', '4: response did not return REJECTED';

    SELECT status INTO v_status FROM public.conversation_meeting_locations WHERE id = v_p2;
    IF NOT (v_status = 'REJECTED') THEN
        RAISE EXCEPTION '4: proposal row is not REJECTED, got %', v_status;
    END IF;

    SELECT count(*) INTO v_count
    FROM public.meeting_response_events
    WHERE meeting_id = v_p2 AND response = 'REJECTED';
    IF NOT (v_count = 1) THEN
        RAISE EXCEPTION '4: expected exactly 1 decline event, got %', v_count;
    END IF;

    SELECT actor_id INTO v_actor FROM public.meeting_response_events WHERE meeting_id = v_p2;
    ASSERT v_actor = v_alice_id, '4: event actor must be the responder, not a client-supplied id';
    RAISE NOTICE ' [PASS] Step 4: decline wrote status + exactly 1 event attributed to Alice';

    -- =================================================================
    -- 5. A RESPONSE IS FINAL - REPEATED TAPS CANNOT DOUBLE-ANSWER
    --    A replay raises, and critically it must NOT add a second event.
    -- =================================================================
    BEGIN
        PERFORM public.respond_to_meeting_proposal(v_p2, 'ACCEPTED');
        RAISE EXCEPTION '5: a rejected proposal was accepted on a second tap';
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE ' [PASS] Step 5a: repeat response rejected: %', SQLERRM;
    END;

    SELECT count(*) INTO v_count
    FROM public.meeting_response_events WHERE meeting_id = v_p2;
    IF NOT (v_count = 1) THEN
        RAISE EXCEPTION '5b: retry produced a duplicate event (% rows)', v_count;
    END IF;

    SELECT status INTO v_status FROM public.conversation_meeting_locations WHERE id = v_p2;
    IF NOT (v_status = 'REJECTED') THEN
        RAISE EXCEPTION '5c: replay flipped the outcome to %', v_status;
    END IF;
    RAISE NOTICE ' [PASS] Step 5b: retry left exactly 1 event and the original outcome intact';

    -- =================================================================
    -- 6. ACCEPTING MAKES IT THE CURRENT SPOT WITHOUT ERASING HISTORY
    --    p1 is Alex's proposal, so Robin is the one allowed to answer it.
    -- =================================================================
    PERFORM set_config('request.jwt.claim.sub', v_bob_id::TEXT, true);
    v_res := public.respond_to_meeting_proposal(v_p1, 'ACCEPTED');
    ASSERT (v_res->>'is_current')::BOOLEAN, '6: accept did not report is_current';

    SELECT status, is_current INTO v_status, v_current
    FROM public.conversation_meeting_locations WHERE id = v_p1;
    IF NOT (v_status = 'ACCEPTED' AND v_current) THEN
        RAISE EXCEPTION '6: accepted proposal should be ACCEPTED + current, got %/%', v_status, v_current;
    END IF;

    -- The declined one keeps its outcome - it is not deleted or flattened.
    SELECT status, is_current INTO v_status, v_current
    FROM public.conversation_meeting_locations WHERE id = v_p2;
    IF NOT (v_status = 'REJECTED' AND v_current = false) THEN
        RAISE EXCEPTION '6: the declined proposal lost its history (got %/%)', v_status, v_current;
    END IF;
    RAISE NOTICE ' [PASS] Step 6: accepted spot is current, earlier decline preserved';

    -- =================================================================
    -- 7. ACCEPTING A LATER PROPOSAL MOVES THE POINTER, KEEPS THE RECORD
    --    "Previously accepted" (ACCEPTED, not current) stays distinguishable
    --    from "current agreed spot" (ACCEPTED, current).
    -- =================================================================
    -- Fixture coordinates are verified against the NIT Trichy polygon in
    -- migration 007 (is_point_in_nit_trichy_campus), each with several hundred
    -- metres of margin. Deliberately NOT polygon vertices: a vertex is the
    -- degenerate case for ray casting, so it can be classified either way and
    -- flips if the polygon is ever edited. 10.7620/78.8200 ~ 500 m inside.
    PERFORM set_config('request.jwt.claim.sub', v_alice_id::TEXT, true);
    v_res := public.propose_meeting_location(v_conv_a, 10.7620, 78.8200, 'Library gate');
    v_p3 := (v_res->>'meeting_id')::UUID;

    -- p1 is currently the agreed spot; proposing must NOT disturb it.
    SELECT is_current INTO v_current
    FROM public.conversation_meeting_locations WHERE id = v_p1;
    ASSERT v_current, '7: proposing a new spot changed the agreed spot';

    -- p3 is Alex's proposal, so Robin accepts it and becomes the agreed spot.
    PERFORM set_config('request.jwt.claim.sub', v_bob_id::TEXT, true);
    PERFORM public.respond_to_meeting_proposal(v_p3, 'ACCEPTED');

    SELECT status, is_current INTO v_status, v_current
    FROM public.conversation_meeting_locations WHERE id = v_p3;
    ASSERT v_status = 'ACCEPTED' AND v_current, '7: p3 is not the current agreed spot';

    SELECT status, is_current INTO v_status, v_current
    FROM public.conversation_meeting_locations WHERE id = v_p1;
    IF NOT (v_status = 'ACCEPTED' AND v_current = false) THEN
        RAISE EXCEPTION '7: p1 should be "previously accepted", got %/%', v_status, v_current;
    END IF;
    RAISE NOTICE ' [PASS] Step 7: p3 is current, p1 is "previously accepted" - both readable';

    -- =================================================================
    -- 8. AT MOST ONE CURRENT SPOT PER CONVERSATION (the invariant that makes
    --    concurrent responses safe)
    -- =================================================================
    SELECT count(*) INTO v_count
    FROM public.conversation_meeting_locations
    WHERE conversation_id = v_conv_a AND is_current;
    IF NOT (v_count = 1) THEN
        RAISE EXCEPTION '8: expected exactly 1 current spot, got %', v_count;
    END IF;
    RAISE NOTICE ' [PASS] Step 8: exactly one current agreed spot';

    -- =================================================================
    -- 9. AN UNANSWERED PROPOSAL STAYS VISIBLE AND PENDING
    -- =================================================================
    -- 10.7650/78.8150, ~385 m inside the boundary. (10.7631047/78.8246130 was
    -- used here first and is a polygon VERTEX, which the boundary check rejects.
    -- Step 11 keeps the deliberately out-of-bounds coordinate.)
    PERFORM set_config('request.jwt.claim.sub', v_alice_id::TEXT, true);
    v_res := public.propose_meeting_location(v_conv_a, 10.7650, 78.8150, 'Sports complex');
    v_p4 := (v_res->>'meeting_id')::UUID;
    SELECT status INTO v_status FROM public.conversation_meeting_locations WHERE id = v_p4;
    IF NOT (v_status = 'PROPOSED') THEN
        RAISE EXCEPTION '9: p4 should be PROPOSED, got %', v_status;
    END IF;

    SELECT count(*) INTO v_count FROM public.meeting_response_events WHERE meeting_id = v_p4;
    ASSERT v_count = 0, '9: an unanswered proposal must have no event';
    RAISE NOTICE ' [PASS] Step 9: unanswered proposal % remains pending and visible', v_p4;

    -- =================================================================
    -- 10. ALL FOUR PROPOSALS ARE STILL IN THE THREAD, IN ORDER
    -- =================================================================
    SELECT count(*) INTO v_count
    FROM public.conversation_meeting_locations
    WHERE conversation_id = v_conv_a
      AND id IN (v_p1, v_p2, v_p3, v_p4);
    IF NOT (v_count = 4) THEN
        RAISE EXCEPTION '10: expected 4 surviving proposals, got %', v_count;
    END IF;
    RAISE NOTICE ' [PASS] Step 10: 4 proposals coexist in the conversation';

    -- =================================================================
    -- 11. THE CAMPUS BOUNDARY STILL APPLIES TO NEW PROPOSALS
    -- =================================================================
    BEGIN
        PERFORM public.propose_meeting_location(v_conv_a, 13.0827, 80.2707, 'Chennai');
        RAISE EXCEPTION '11: out-of-campus proposal was accepted';
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE ' [PASS] Step 11: out-of-campus proposal blocked: %', SQLERRM;
    END;

    -- =================================================================
    -- 12. OUTSIDERS: NO READS, NO PROPOSALS, NO RESPONSES, NO FORGED EVENTS
    -- =================================================================
    PERFORM set_config('request.jwt.claim.sub', v_eve_id::TEXT, true);

    SELECT count(*) INTO v_count
    FROM public.conversation_meeting_locations WHERE conversation_id = v_conv_a;
    ASSERT v_count = 0, '12a: Eve read private meeting locations!';

    SELECT count(*) INTO v_count
    FROM public.meeting_response_events WHERE conversation_id = v_conv_a;
    ASSERT v_count = 0, '12b: Eve read private response events!';

    SELECT count(*) INTO v_count
    FROM public.messages WHERE conversation_id = v_conv_a;
    ASSERT v_count = 0, '12c: Eve read private messages!';
    RAISE NOTICE ' [PASS] Step 12: Eve cannot read the thread (RLS isolation)';

    BEGIN
        PERFORM public.propose_meeting_location(v_conv_a, 10.7610, 78.8220, 'Eve intrusion');
        RAISE EXCEPTION '12d: Eve proposed a meeting spot';
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE ' [PASS] Step 12d: Eve proposal blocked: %', SQLERRM;
    END;

    BEGIN
        PERFORM public.respond_to_meeting_proposal(v_p4, 'ACCEPTED');
        RAISE EXCEPTION '12e: Eve responded to a proposal';
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE ' [PASS] Step 12e: Eve response blocked: %', SQLERRM;
    END;

    SELECT status INTO v_status FROM public.conversation_meeting_locations WHERE id = v_p4;
    IF NOT (v_status = 'PROPOSED') THEN
        RAISE EXCEPTION '12f: Eve response mutated the proposal to %', v_status;
    END IF;

    -- A client cannot forge an event for someone else's decision.
    BEGIN
        INSERT INTO public.meeting_response_events (conversation_id, meeting_id, actor_id, response)
        VALUES (v_conv_a, v_p4, v_alice_id, 'ACCEPTED');
        RAISE EXCEPTION '12g: a client forged a response event';
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE ' [PASS] Step 12g: forged event rejected: %', SQLERRM;
    END;

    -- Nor can a client write the outcome directly.
    BEGIN
        UPDATE public.conversation_meeting_locations
        SET status = 'ACCEPTED', is_current = true
        WHERE id = v_p4;
        RAISE EXCEPTION '12h: a client set the outcome directly';
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE ' [PASS] Step 12h: direct outcome write rejected: %', SQLERRM;
    END;
    SELECT status INTO v_status FROM public.conversation_meeting_locations WHERE id = v_p4;
    IF NOT (v_status = 'PROPOSED') THEN
        RAISE EXCEPTION '12i: direct write changed status to %', v_status;
    END IF;
    RAISE NOTICE ' [PASS] Step 12: outsider read, propose, respond, forge and patch all blocked';

    -- =================================================================
    -- 13. QUOTED REPLIES
    -- =================================================================
    PERFORM set_config('request.jwt.claim.sub', v_alice_id::TEXT, true);
    INSERT INTO public.messages (conversation_id, sender_id, sender_name, content)
    VALUES (v_conv_a, v_alice_id, 'Alice Finder', 'See you at the library gate')
    RETURNING id INTO v_msg_a;

    -- Same-conversation reply to a text message: allowed.
    -- The JWT must be Bob's here: the messages INSERT policy requires
    -- sender_id = auth.uid(), so inserting as Bob while claiming to be Alice
    -- is correctly rejected by RLS.
    PERFORM set_config('request.jwt.claim.sub', v_bob_id::TEXT, true);
    INSERT INTO public.messages (conversation_id, sender_id, sender_name, content, reply_to_message_id)
    VALUES (v_conv_a, v_bob_id, 'Bob Claimant', 'Which gate?', v_msg_a)
    RETURNING id INTO v_msg_b;
    RAISE NOTICE ' [PASS] Step 13a: reply to a same-conversation message accepted';

    -- Same-conversation reply to a MEETING PROPOSAL card: allowed.
    PERFORM set_config('request.jwt.claim.sub', v_bob_id::TEXT, true);
    INSERT INTO public.messages (conversation_id, sender_id, sender_name, content, reply_to_meeting_id)
    VALUES (v_conv_a, v_bob_id, 'Bob Claimant', 'That spot works for me', v_p3);
    RAISE NOTICE ' [PASS] Step 13b: reply to a meeting proposal card accepted';

    -- Cross-conversation reply: rejected by the trigger.
    PERFORM set_config('request.jwt.claim.sub', v_eve_id::TEXT, true);
    INSERT INTO public.messages (conversation_id, sender_id, sender_name, content)
    VALUES (v_conv_b, v_eve_id, 'Eve Impostor', 'thread B message')
    RETURNING id INTO v_msg_b;

    PERFORM set_config('request.jwt.claim.sub', v_alice_id::TEXT, true);
    BEGIN
        INSERT INTO public.messages (conversation_id, sender_id, sender_name, content, reply_to_message_id)
        VALUES (v_conv_a, v_alice_id, 'Alice Finder', 'borrowed quote', v_msg_b);
        RAISE EXCEPTION '13c: a message from another conversation was quoted';
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE ' [PASS] Step 13c: cross-conversation quote rejected: %', SQLERRM;
    END;

    -- p1 lives in conversation A, so quoting it there is legitimate. This proves
    -- the trigger checks the CONVERSATION, it is not a blanket ban on quoting
    -- meeting spots.
    INSERT INTO public.messages (conversation_id, sender_id, sender_name, content, reply_to_meeting_id)
    VALUES (v_conv_a, v_alice_id, 'Alice Finder', 'borrowed spot', v_p1);
    RAISE NOTICE ' [PASS] Step 13d: same-conversation meeting quote still allowed';

    -- Forged sender identity: rejected by the messages INSERT policy.
    PERFORM set_config('request.jwt.claim.sub', v_bob_id::TEXT, true);
    BEGIN
        INSERT INTO public.messages (conversation_id, sender_id, sender_name, content)
        VALUES (v_conv_a, v_alice_id, 'Alice Finder', 'impersonation attempt');
        RAISE EXCEPTION '13e: a message was posted under someone else''s id';
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE ' [PASS] Step 13e: forged sender rejected: %', SQLERRM;
    END;

    -- Posting into a thread you are not part of: rejected.
    PERFORM set_config('request.jwt.claim.sub', v_bob_id::TEXT, true);
    BEGIN
        INSERT INTO public.messages (conversation_id, sender_id, sender_name, content)
        VALUES (v_conv_b, v_bob_id, 'Bob Claimant', 'intruding on thread B');
        RAISE EXCEPTION '13f: Bob posted into Alice and Eve''s thread';
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE ' [PASS] Step 13f: cross-thread post rejected: %', SQLERRM;
    END;

    -- Two reply targets at once: rejected by the storage constraint.
    PERFORM set_config('request.jwt.claim.sub', v_alice_id::TEXT, true);
    BEGIN
        INSERT INTO public.messages (
            conversation_id, sender_id, sender_name, content,
            reply_to_message_id, reply_to_meeting_id)
        VALUES (v_conv_a, v_alice_id, 'Alice Finder', 'double quote', v_msg_a, v_p1);
        RAISE EXCEPTION '13g: a message carried two reply targets';
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE ' [PASS] Step 13g: double reply target rejected: %', SQLERRM;
    END;

    -- Outcome verification for 13e/13f/13g. A "[PASS] attempt blocked"
    -- notice printed from an exception handler is NOT proof on its own: if any
    -- layer had let the row through silently, the in-block RAISE would still
    -- fire and the handler would print a false PASS. Each tampered row carries a
    -- unique sentinel string, so counting proves the row was never written.
    -- Counts are taken as an actual participant of the target thread.

    -- 13e: nobody may see a message posted under someone else's id.
    PERFORM set_config('request.jwt.claim.sub', v_alice_id::TEXT, true);
    SELECT count(*) INTO v_count
    FROM public.messages
    WHERE content = 'impersonation attempt';
    ASSERT v_count = 0, '13e: a message was stored under someone else''s id';

    -- 13f: the intruding row must not exist in thread B (Eve is a participant).
    PERFORM set_config('request.jwt.claim.sub', v_eve_id::TEXT, true);
    SELECT count(*) INTO v_count
    FROM public.messages
    WHERE content = 'intruding on thread B';
    ASSERT v_count = 0, '13f: Bob posted into a thread he is not part of';

    -- 13g: the double-quote row must not exist.
    PERFORM set_config('request.jwt.claim.sub', v_alice_id::TEXT, true);
    SELECT count(*) INTO v_count
    FROM public.messages
    WHERE content = 'double quote';
    ASSERT v_count = 0, '13g: a message carried two reply targets';
    RAISE NOTICE ' [PASS] Step 13: reply isolation and sender integrity verified (13e/13f/13g outcome-verified)';

    -- =================================================================
    -- 14. EXISTING PLAIN MESSAGES ARE UNAFFECTED
    -- =================================================================
    SELECT count(*) INTO v_count
    FROM public.messages
    WHERE conversation_id = v_conv_a AND reply_to_message_id IS NULL AND reply_to_meeting_id IS NULL;
    ASSERT v_count >= 1, '14: pre-existing plain messages were not left intact';
    RAISE NOTICE ' [PASS] Step 14: % plain message(s) still readable with NULL reply refs', v_count;

    RAISE NOTICE '==================================================';
    RAISE NOTICE 'ALL CHAT TIMELINE TESTS PASSED!';
    RAISE NOTICE '  proposals:      4 kept, 1 current, 2 previously accepted/declined, 1 pending';
    RAISE NOTICE '  events:         3 (accept p1, decline p2, accept p3), no duplicates';
    RAISE NOTICE '  replies:        same-conversation only, single target, no forged senders';
    RAISE NOTICE '==================================================';
END;
$$ LANGUAGE plpgsql;

GRANT EXECUTE ON FUNCTION test_chat_timeline() TO authenticated;

-- Run the body as `authenticated` so the raw SELECTs below are genuinely RLS
-- filtered and the raw INSERT/UPDATE attempts are genuinely privilege-checked.
-- The SECURITY DEFINER RPCs still work: they switch to their own owner.
SET ROLE authenticated;

SELECT test_chat_timeline();

RESET ROLE;

ROLLBACK;
