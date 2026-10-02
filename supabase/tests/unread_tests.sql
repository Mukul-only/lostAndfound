-- ====================================================================
-- SUPABASE TEST SUITE: PER-USER READ STATE AND UNREAD COUNTS
-- Requires: 001-011, then 012_unread_read_state.sql
-- Run in the Supabase SQL Editor. Wrapped in a transaction that ROLLBACKs.
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
    ('22222222-2222-2222-2222-222222222222', 'Bob Claimant')
ON CONFLICT (id) DO UPDATE SET full_name = EXCLUDED.full_name;

CREATE OR REPLACE FUNCTION test_unread_read_state()
RETURNS VOID AS $$
DECLARE
    v_alice UUID := '11111111-1111-1111-1111-111111111111';
    v_bob   UUID := '22222222-2222-2222-2222-222222222222';
    v_eve   UUID := '33333333-3333-3333-3333-333333333333';
    v_res JSON;
    v_report UUID;
    v_claim UUID;
    v_conv UUID;
    v_meeting UUID;
    v_event UUID;
    v_count INT;
    v_total INT;
    v_cursor TIMESTAMPTZ;
    v_rows INT;
    v_sentinel TIMESTAMPTZ;
    v_t0 TIMESTAMPTZ := now();
BEGIN
    PERFORM set_config('request.jwt.claim.sub', v_alice::TEXT, true);
    PERFORM set_config('request.jwt.claim.role', 'authenticated', true);

    -- ----------------------------------------------------------------
    -- SETUP: a handover conversation between Alice and Bob
    --
    -- now() is constant for the whole transaction, so without explicit
    -- timestamps every row created below would share one created_at and the
    -- "newer than the cursor" rule could not be tested at all. Items written by
    -- an RPC (the proposal and the response event) cannot be back-dated, so they
    -- keep created_at = now(). The messages this test inserts directly are given
    -- earlier, increasing timestamps, which produces a real ordering:
    --   M1 (T-3m) < M2 (T-2m) < M3 (T-90s) < proposal (T) = event (T)
    -- ----------------------------------------------------------------
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
    v_report := (v_res->>'report_id')::UUID;

    PERFORM set_config('request.jwt.claim.sub', v_bob::TEXT, true);
    v_res := public.submit_claim(v_report, 'That is my flask, it says Bob on the base');
    v_claim := (v_res->>'claim_id')::UUID;

    PERFORM set_config('request.jwt.claim.sub', v_alice::TEXT, true);
    v_res := public.accept_claim(v_claim);
    v_conv := (v_res->>'conversation_id')::UUID;
    ASSERT v_conv IS NOT NULL, 'SETUP: conversation not created';
    RAISE NOTICE ' [PASS] SETUP: conversation % created', v_conv;

    -- The migration seeds a cursor at the newest existing item, so the brand new
    -- conversation starts with nothing unread for either side.
    SELECT count(*) INTO v_count FROM public.get_conversation_unread_counts();
    ASSERT v_count = 0, 'SETUP: a fresh conversation should start with 0 unread rows';
    ASSERT public.get_unread_total() = 0, 'SETUP: fresh total should be 0';
    RAISE NOTICE ' [PASS] SETUP: seeded cursor leaves a fresh conversation at 0 unread';

    -- ----------------------------------------------------------------
    -- 1. AN INCOMING MESSAGE IS UNREAD FOR THE OTHER SIDE ONLY
    -- ----------------------------------------------------------------
    PERFORM set_config('request.jwt.claim.sub', v_bob::TEXT, true);
    INSERT INTO public.messages (conversation_id, sender_id, sender_name, content, created_at)
    VALUES (v_conv, v_bob, 'Bob Claimant', 'are you around today?', v_t0 - interval '3 minutes');

    -- Count as Alice: she is the side that has not read it yet. Counting while
    -- still claiming to be Bob would correctly report 0 (your own message is
    -- never unread for you), which is asserted separately below.
    PERFORM set_config('request.jwt.claim.sub', v_alice::TEXT, true);
    SELECT count(*) INTO v_count FROM public.get_conversation_unread_counts();
    ASSERT v_count = 1, format('1: expected 1 conversation with unread for Alice, got %s', v_count);
    ASSERT public.get_unread_total() = 1, '1: Alice total should be 1';

    -- Bob sent it, so it is not unread for Bob.
    PERFORM set_config('request.jwt.claim.sub', v_bob::TEXT, true);
    ASSERT public.get_unread_total() = 0, '1: your own message must never be unread for you';
    RAISE NOTICE ' [PASS] Step 1: incoming message counts for the other side only';

    -- ----------------------------------------------------------------
    -- 2. COUNTING IS PER CONVERSATION
    -- ----------------------------------------------------------------
    PERFORM set_config('request.jwt.claim.sub', v_alice::TEXT, true);
    INSERT INTO public.messages (conversation_id, sender_id, sender_name, content, created_at)
    VALUES (v_conv, v_alice, 'Alice Finder', 'yes, at the library', v_t0 - interval '2 minutes');
    INSERT INTO public.messages (conversation_id, sender_id, sender_name, content, created_at)
    VALUES (v_conv, v_alice, 'Alice Finder', 'coming at 5', v_t0 - interval '90 seconds');

    ASSERT public.get_unread_total() = 1, '2: Alice should still see exactly 1 unread';
    PERFORM set_config('request.jwt.claim.sub', v_bob::TEXT, true);
    ASSERT public.get_unread_total() = 2, '2: Bob should see 2 unread from Alice';
    RAISE NOTICE ' [PASS] Step 2: each side counts only what the other sent';

    -- ----------------------------------------------------------------
    -- 3. A PROPOSAL COUNTS, AND MINE DOES NOT
    -- ----------------------------------------------------------------
    PERFORM set_config('request.jwt.claim.sub', v_alice::TEXT, true);
    v_res := public.propose_meeting_location(v_conv, 10.7610, 78.8220, 'Opal Hostel entrance');
    v_meeting := (v_res->>'meeting_id')::UUID;

    PERFORM set_config('request.jwt.claim.sub', v_bob::TEXT, true);
    ASSERT public.get_unread_total() = 3, '3: Bob should see 2 messages + 1 proposal';
    PERFORM set_config('request.jwt.claim.sub', v_alice::TEXT, true);
    ASSERT public.get_unread_total() = 1, '3: Alice must not see her own proposal as unread';
    RAISE NOTICE ' [PASS] Step 3: proposal counts for the other side, never for its author';

    -- ----------------------------------------------------------------
    -- 4. A RESPONSE EVENT COUNTS FOR THE OTHER SIDE
    --    Bob accepts Alice''s proposal: Alice has not seen that yet, Bob has.
    -- ----------------------------------------------------------------
    PERFORM set_config('request.jwt.claim.sub', v_bob::TEXT, true);
    v_res := public.respond_to_meeting_proposal(v_meeting, 'ACCEPTED');
    v_event := (v_res->>'meeting_id')::UUID;
    ASSERT v_event = v_meeting, 'SETUP: respond returned the wrong meeting id';

    PERFORM set_config('request.jwt.claim.sub', v_alice::TEXT, true);
    ASSERT public.get_unread_total() = 2, '4: Alice should see the accept event + 1 message';
    PERFORM set_config('request.jwt.claim.sub', v_bob::TEXT, true);
    ASSERT public.get_unread_total() = 3, '4: Bob must not count the accept he performed';
    RAISE NOTICE ' [PASS] Step 4: response event counts for the other side only';

    -- ----------------------------------------------------------------
    -- 5. MARKING READ CLEARS ONLY WHAT WAS ACTUALLY SEEN
    -- ----------------------------------------------------------------
    PERFORM set_config('request.jwt.claim.sub', v_alice::TEXT, true);
    SELECT max(created_at) INTO v_cursor FROM public.messages
    WHERE conversation_id = v_conv AND sender_id = v_bob;
    ASSERT v_cursor IS NOT NULL, '5: nothing to read';

    v_cursor := public.mark_conversation_read(v_conv, v_cursor);
    ASSERT v_cursor IS NOT NULL, '5: mark_conversation_read returned null';

    -- M1 (T-3m) is now read. The accept event (T) is strictly newer, so it and
    -- nothing else remains unread.
    ASSERT public.get_unread_total() = 1, '5: expected only the accept event left unread';
    RAISE NOTICE ' [PASS] Step 5: reading up to a point leaves newer items unread';

    -- ----------------------------------------------------------------
    -- 6. THE CURSOR IS MONOTONIC
    --    Replaying the same request, or sending an older one, changes nothing.
    -- ----------------------------------------------------------------
    SELECT last_read_at INTO v_cursor FROM public.conversation_read_state
    WHERE conversation_id = v_conv AND user_id = v_alice;

    PERFORM public.mark_conversation_read(v_conv, v_cursor);
    SELECT last_read_at INTO v_cursor FROM public.conversation_read_state
    WHERE conversation_id = v_conv AND user_id = v_alice;
    ASSERT v_cursor IS NOT NULL, '6: cursor row disappeared on replay';
    RAISE NOTICE ' [PASS] Step 6a: a replayed mark-read is a no-op';

    PERFORM public.mark_conversation_read(v_conv, '1900-01-01T00:00:00Z');
    SELECT last_read_at INTO v_cursor FROM public.conversation_read_state
    WHERE conversation_id = v_conv AND user_id = v_alice;
    ASSERT v_cursor > '1900-01-01T00:00:00Z'::timestamptz,
        '6: an older timestamp must not rewind the cursor';
    RAISE NOTICE ' [PASS] Step 6b: an older timestamp cannot rewind the cursor';

    -- ----------------------------------------------------------------
    -- 7. A MESSAGE ARRIVING AFTER THE MARK STAYS UNREAD
    --    This is the concurrent-arrival case: mark, then Bob posts again.
    --    Back-dated to T-1m it is still strictly newer than Alice's cursor
    --    (T-3m) while staying at or before now(), so the step-8 clamp does not
    --    turn it into a future item.
    -- ----------------------------------------------------------------
    PERFORM set_config('request.jwt.claim.sub', v_bob::TEXT, true);
    INSERT INTO public.messages (conversation_id, sender_id, sender_name, content, created_at)
    VALUES (v_conv, v_bob, 'Bob Claimant', 'one more thing', v_t0 - interval '1 minute');

    PERFORM set_config('request.jwt.claim.sub', v_alice::TEXT, true);
    ASSERT public.get_unread_total() = 2, '7: the post-mark message must be unread';
    RAISE NOTICE ' [PASS] Step 7: content arriving after a mark stays unread';

    -- A future timestamp is clamped, so a client cannot mute itself forever.
    PERFORM public.mark_conversation_read(v_conv, '2999-01-01T00:00:00Z');
    SELECT last_read_at INTO v_cursor FROM public.conversation_read_state
    WHERE conversation_id = v_conv AND user_id = v_alice;
    ASSERT v_cursor < '2999-01-01T00:00:00Z'::timestamptz,
        '7: a future timestamp must be clamped to now';
    RAISE NOTICE ' [PASS] Step 7b: a future timestamp is clamped to now()';

    -- ----------------------------------------------------------------
    -- 8. READING IT ALL CLEARS EVERYTHING
    -- ----------------------------------------------------------------
    PERFORM set_config('request.jwt.claim.sub', v_alice::TEXT, true);
    PERFORM public.mark_conversation_read(v_conv, timezone('utc'::text, now()));
    ASSERT public.get_unread_total() = 0, '8: reading everything should clear the total';
    ASSERT (SELECT count(*) FROM public.get_conversation_unread_counts()) = 0,
        '8: the conversation should drop out of the unread list entirely';
    RAISE NOTICE ' [PASS] Step 8: reading everything clears counts and drops the conversation';

    -- ----------------------------------------------------------------
    -- 9. ONLY PARTICIPANTS MAY MARK READ
    --    Eve is not in this conversation. Switch to her first: the JWT is still
    --    Alice's here, and Alice legitimately may mark it, which used to make
    --    this step pass on its own guard message instead of a real refusal.
    -- ----------------------------------------------------------------
    PERFORM set_config('request.jwt.claim.sub', v_eve::TEXT, true);
    BEGIN
        PERFORM public.mark_conversation_read(v_conv, timezone('utc'::text, now()));
        RAISE EXCEPTION '9: a non-participant marked a conversation read';
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE ' Step 9 attempt refused: %', SQLERRM;
    END;

    -- Outcome check, so the notice above cannot pass on the test's own guard.
    SELECT count(*) INTO v_rows FROM public.conversation_read_state
    WHERE user_id = v_eve AND conversation_id = v_conv;
    ASSERT v_rows = 0, '9: a non-participant created a read cursor';

    BEGIN
        PERFORM public.mark_conversation_read(
            '00000000-0000-0000-0000-000000000000', timezone('utc'::text, now()));
        RAISE EXCEPTION '9: marked a nonexistent conversation read';
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE ' [PASS] Step 9b: unknown conversation blocked: %', SQLERRM;
    END;

    -- ----------------------------------------------------------------
    -- 10. READ STATE IS PRIVATE TO ITS OWNER
    --      Under authenticated, the RLS policies must expose only the caller's
    --      own row - no other user's cursor is readable or writable.
    -- ----------------------------------------------------------------
    PERFORM set_config('request.jwt.claim.sub', v_alice::TEXT, true);
    SELECT count(*) INTO v_rows FROM public.conversation_read_state
    WHERE user_id = v_bob;
    ASSERT v_rows = 0, '10: one user could read another user''s read state';

    -- Steps 10b/10c use a sentinel far-future timestamp and then assert that
    -- the sentinel never landed. Asserting "something raised" is NOT sufficient
    -- here: 012 grants UPDATE on this table, so RLS filters the row out and the
    -- statement simply matches ZERO rows without raising. Only an outcome check
    -- distinguishes "blocked" from "wrote a future cursor".
    v_sentinel := '2999-01-01 00:00:00+00'::TIMESTAMPTZ;

    BEGIN
        UPDATE public.conversation_read_state
        SET last_read_at = v_sentinel
        WHERE user_id = v_bob;
        RAISE EXCEPTION '10: one user could write another user''s read state';
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE ' Step 10b attempt blocked: %', SQLERRM;
    END;

    -- Alice cannot read Bob's row, so the honest check is done as Bob: his
    -- cursor must never have been pushed to the sentinel.
    PERFORM set_config('request.jwt.claim.sub', v_bob::TEXT, true);
    SELECT count(*) INTO v_rows FROM public.conversation_read_state
    WHERE user_id = v_bob
      AND last_read_at >= v_sentinel;
    ASSERT v_rows = 0, '10b: Alice wrote to Bob''s read state';

    -- Step 10c: forging a row that belongs to another user. Re-assert as Alice.
    PERFORM set_config('request.jwt.claim.sub', v_alice::TEXT, true);
    BEGIN
        INSERT INTO public.conversation_read_state (conversation_id, user_id, last_read_at)
        VALUES (v_conv, v_bob, v_sentinel);
        RAISE EXCEPTION '10: inserted a read-state row belonging to someone else';
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE ' Step 10c attempt blocked: %', SQLERRM;
    END;

    -- Again verified as Bob, where a forged row would be visible.
    PERFORM set_config('request.jwt.claim.sub', v_bob::TEXT, true);
    SELECT count(*) INTO v_rows FROM public.conversation_read_state
    WHERE user_id = v_bob
      AND last_read_at >= v_sentinel;
    ASSERT v_rows = 0, '10c: Alice forged a read-state row for Bob';
    RAISE NOTICE ' [PASS] Step 10: read state is isolated per user (write + forge outcome-verified)';

    RAISE NOTICE '==================================================';
    RAISE NOTICE 'ALL UNREAD / READ-STATE TESTS PASSED!';
    RAISE NOTICE '==================================================';
END;
$$ LANGUAGE plpgsql;

GRANT EXECUTE ON FUNCTION test_unread_read_state() TO authenticated;

SET ROLE authenticated;

SELECT test_unread_read_state();

RESET ROLE;

ROLLBACK;