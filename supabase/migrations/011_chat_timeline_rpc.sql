-- ====================================================================
-- SUPABASE MIGRATION 011: SERVER-AUTHORIZED MEETING PROPOSALS & RESPONSES
-- ====================================================================
-- Requires 010_chat_timeline_schema.sql.
--
-- Three functions, all SECURITY DEFINER (the 010 grant lock means authenticated
-- has no direct INSERT/UPDATE on conversation_meeting_locations, so these are
-- the only writers):
--
--   propose_meeting_location    one call == one new proposal card. The 006/007
--                               version superseded every earlier PROPOSED and
--                               CONFIRMED row, which destroyed history; that
--                               step is gone. Earlier proposals keep their own
--                               identity and their own outcome, and an
--                               unanswered proposal simply stays unanswered.
--
--   respond_to_meeting_proposal accept OR decline. The status change and the
--                               centered timeline event are written in one
--                               transaction, so a failure can never leave an
--                               event without its outcome. SELECT ... FOR UPDATE
--                               plus a `status = 'PROPOSED'` guard on the update
--                               makes a replay or a concurrent second tap fail
--                               cleanly instead of double-answering.
--
--   confirm_meeting_location    thin backward-compatible wrapper over
--                               respond_to_meeting_proposal(..., 'ACCEPTED').
--                               The app no longer calls it.
-- ====================================================================


-- --------------------------------------------------------------------
-- 1. PROPOSE A MEETING SPOT (history-preserving)
-- --------------------------------------------------------------------
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

    SELECT * INTO v_conv FROM public.conversations WHERE id = p_conversation_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Conversation not found';
    END IF;

    IF v_conv.owner_id <> v_user_id AND v_conv.claimant_id <> v_user_id THEN
        RAISE EXCEPTION 'Only conversation participants can propose a meeting location';
    END IF;

    IF p_latitude IS NULL OR p_longitude IS NULL THEN
        RAISE EXCEPTION 'Both latitude and longitude are required';
    END IF;
    IF p_latitude < -90.0 OR p_latitude > 90.0 THEN
        RAISE EXCEPTION 'Invalid latitude';
    END IF;
    IF p_longitude < -180.0 OR p_longitude > 180.0 THEN
        RAISE EXCEPTION 'Invalid longitude';
    END IF;

    -- NIT Trichy campus boundary is unchanged from 007.
    IF NOT public.is_point_in_nit_trichy_campus(p_latitude, p_longitude) THEN
        RAISE EXCEPTION 'Proposed meeting location coordinates are outside the NIT Trichy campus boundary. Pins must be placed within campus.';
    END IF;

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

    -- The proposal row IS the timeline item, so this single INSERT inside the
    -- RPC transaction is the whole atomic create. Earlier proposals are left
    -- completely untouched.
    INSERT INTO public.conversation_meeting_locations (
        conversation_id,
        proposer_id,
        latitude,
        longitude,
        location_note,
        status,
        is_current,
        created_at,
        updated_at
    ) VALUES (
        p_conversation_id,
        v_user_id,
        p_latitude,
        p_longitude,
        v_trimmed_note,
        'PROPOSED',
        false,
        timezone('utc'::text, now()),
        timezone('utc'::text, now())
    ) RETURNING id INTO v_new_id;

    RETURN json_build_object(
        'success', true,
        'meeting_id', v_new_id,
        'status', 'PROPOSED',
        'is_current', false
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

REVOKE EXECUTE ON FUNCTION public.propose_meeting_location(UUID, DOUBLE PRECISION, DOUBLE PRECISION, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.propose_meeting_location(UUID, DOUBLE PRECISION, DOUBLE PRECISION, TEXT) TO authenticated;


-- --------------------------------------------------------------------
-- 2. RESPOND TO A PROPOSAL (accept or decline) + the centered event
-- --------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.respond_to_meeting_proposal(
    p_meeting_id UUID,
    p_response TEXT
)
RETURNS JSON AS $$
DECLARE
    v_user_id UUID;
    v_response TEXT;
    v_meeting public.conversation_meeting_locations%ROWTYPE;
    v_conv public.conversations%ROWTYPE;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    v_response := upper(trim(coalesce(p_response, '')));
    IF v_response NOT IN ('ACCEPTED', 'REJECTED') THEN
        RAISE EXCEPTION 'Response must be ACCEPTED or REJECTED';
    END IF;

    -- Row lock first. Two concurrent taps on the same proposal serialise here;
    -- the loser sees the new status below and gets a clear error.
    SELECT * INTO v_meeting
    FROM public.conversation_meeting_locations
    WHERE id = p_meeting_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Handover location not found';
    END IF;

    SELECT * INTO v_conv FROM public.conversations WHERE id = v_meeting.conversation_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Conversation not found';
    END IF;

    IF v_conv.owner_id <> v_user_id AND v_conv.claimant_id <> v_user_id THEN
        RAISE EXCEPTION 'Only conversation participants can respond to a handover location';
    END IF;

    -- The proposer is never the responder. This is the proposal-response
    -- permission rule and it is enforced here, not in the client.
    IF v_meeting.proposer_id = v_user_id THEN
        RAISE EXCEPTION 'You cannot respond to your own handover location. The other student has to accept or decline it.';
    END IF;

    -- A response is final: exactly one transition out of PROPOSED, ever.
    IF v_meeting.status <> 'PROPOSED' THEN
        RAISE EXCEPTION 'This handover location was already %s', v_meeting.status;
    END IF;

    -- Accepting a later spot makes it the agreed one. The previously agreed spot
    -- keeps status ACCEPTED (its history stays true and readable) and only loses
    -- the is_current pointer, which is what distinguishes "previously accepted"
    -- from "the spot you are meeting at now".
    UPDATE public.conversation_meeting_locations
    SET is_current  = false,
        updated_at  = timezone('utc'::text, now())
    WHERE conversation_id = v_meeting.conversation_id
      AND is_current
      AND id <> p_meeting_id;

    -- The `status = 'PROPOSED'` predicate is the second line of defence behind
    -- the row lock: it guarantees the outcome is written at most once.
    UPDATE public.conversation_meeting_locations
    SET status       = v_response,
        responded_by = v_user_id,
        responded_at = timezone('utc'::text, now()),
        is_current   = (v_response = 'ACCEPTED'),
        updated_at   = timezone('utc'::text, now())
    WHERE id = p_meeting_id
      AND status = 'PROPOSED';

    IF NOT FOUND THEN
        RAISE EXCEPTION 'This handover location was already answered';
    END IF;

    -- Same transaction as the status change above: the centered "Alex accepted
    -- the meeting spot." row can never exist without its outcome, and a rollback
    -- takes both. UNIQUE(meeting_id) from 010 makes a replay a no-op error
    -- rather than a duplicate event.
    INSERT INTO public.meeting_response_events (
        conversation_id,
        meeting_id,
        actor_id,
        response,
        created_at
    ) VALUES (
        v_meeting.conversation_id,
        p_meeting_id,
        v_user_id,
        v_response,
        timezone('utc'::text, now())
    );

    RETURN json_build_object(
        'success', true,
        'meeting_id', p_meeting_id,
        'status', v_response,
        'is_current', (v_response = 'ACCEPTED')
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

REVOKE EXECUTE ON FUNCTION public.respond_to_meeting_proposal(UUID, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.respond_to_meeting_proposal(UUID, TEXT) TO authenticated;


-- --------------------------------------------------------------------
-- 3. BACKWARD-COMPATIBLE CONFIRM WRAPPER
-- Kept so anything still calling confirm_meeting_location gets the new
-- history-preserving behaviour (and an event row) instead of a hole.
-- --------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.confirm_meeting_location(
    p_meeting_id UUID
)
RETURNS JSON AS $$
BEGIN
    RETURN public.respond_to_meeting_proposal(p_meeting_id, 'ACCEPTED');
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

REVOKE EXECUTE ON FUNCTION public.confirm_meeting_location(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.confirm_meeting_location(UUID) TO authenticated;
