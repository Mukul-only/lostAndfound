-- ====================================================================
-- SUPABASE MIGRATION 010: PERSISTENT CHAT TIMELINE (PROPOSALS, RESPONSE
--                          EVENTS, QUOTED REPLIES)
-- ====================================================================
-- Incremental only. Nothing in 001-009 is rewritten, and no existing row is
-- deleted. Everything below is additive or a widening of an existing CHECK.
--
-- MIGRATION STRATEGY (existing data is preserved, never rewritten in place
-- destructively):
--   * public.messages                     -> two nullable reply columns, all
--                                            existing rows keep NULL (plain
--                                            text messages, exactly as before)
--   * conversation_meeting_locations      -> 'CONFIRMED' becomes 'ACCEPTED'
--                                            (pure rename of the same value,
--                                            same confirmed_by/confirmed_at),
--                                            'REJECTED' joins the enum, and
--                                            the newest already-confirmed row
--                                            per conversation becomes the
--                                            current agreed spot
--   * meeting_response_events             -> new table, starts empty; past
--                                            acceptances have no event row and
--                                            the app shows the outcome on the
--                                            proposal card instead
--   * 'SUPERSEDED' is kept in the status CHECK purely so rows written by the
--     pre-010 propose_meeting_location still validate. Nothing writes it now.
-- ====================================================================


-- --------------------------------------------------------------------
-- 1. QUOTED REPLIES ON MESSAGES
-- A reply targets exactly one earlier item in the SAME conversation: either a
-- text message or a meeting proposal. Two nullable FKs (not one loose UUID)
-- so referential integrity is enforced by Postgres, and a CHECK so an item can
-- never carry two targets or a dangling non-null one.
-- --------------------------------------------------------------------
ALTER TABLE public.messages
    ADD COLUMN IF NOT EXISTS reply_to_message_id UUID REFERENCES public.messages(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS reply_to_meeting_id UUID REFERENCES public.conversation_meeting_locations(id) ON DELETE SET NULL;

ALTER TABLE public.messages
    DROP CONSTRAINT IF EXISTS chk_messages_single_reply_target;
ALTER TABLE public.messages
    ADD CONSTRAINT chk_messages_single_reply_target
    CHECK (num_nonnulls(reply_to_message_id, reply_to_meeting_id) <= 1);


-- --------------------------------------------------------------------
-- 2. PROPOSAL RESPONSE STATE + "CURRENT AGREED SPOT" POINTER
--
-- status        PROPOSED -> ACCEPTED | REJECTED, written exactly once and never
--               changed again. The response is final.
-- is_current    Exactly one row per conversation is the agreed spot. Accepting
--               a later proposal flips the previous one to false but leaves its
--               status as ACCEPTED, so "previously accepted" stays
--               distinguishable from "current agreed spot" with no duplicated
--               mutable state. The partial unique index is what makes that
--               invariant hold under concurrent responses.
-- --------------------------------------------------------------------
ALTER TABLE public.conversation_meeting_locations
    ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS responded_by UUID REFERENCES auth.users(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS responded_at TIMESTAMPTZ;

-- 2a. Status vocabulary, in THREE ordered steps. Order is load-bearing.
--
--     Postgres validates a new CHECK against existing rows. The inline CHECK
--     that 006 created is auto-named and only permits
--     PROPOSED/CONFIRMED/SUPERSEDED, so the rename below has to be bracketed:
--     you cannot write 'ACCEPTED' while the old CHECK forbids it, and you cannot
--     drop 'CONFIRMED' from the CHECK while rows still hold it. Doing this in one
--     step fails with 23514 on any table that has a confirmed row.
--     (a) replace the CHECK with the UNION of both vocabularies,
--     (b) rename the data,
--     (c) narrow the CHECK to the new vocabulary only.
ALTER TABLE public.conversation_meeting_locations
    DROP CONSTRAINT IF EXISTS conversation_meeting_locations_status_check;
ALTER TABLE public.conversation_meeting_locations
    ADD CONSTRAINT conversation_meeting_locations_status_check
    CHECK (status IN ('PROPOSED', 'CONFIRMED', 'ACCEPTED', 'REJECTED', 'SUPERSEDED'));

-- 2b. Carry the already-confirmed rows over to the new vocabulary. The response
--     columns are backfilled from the confirmed_* columns in the same statement
--     so the answered-row invariant in section 6 holds immediately.
--     responded_at is coalesced so it can never be null; responded_by is taken
--     as-is, which is why section 6 does not require it to be non-null.
UPDATE public.conversation_meeting_locations
SET status      = 'ACCEPTED',
    responded_by = confirmed_by,
    responded_at = coalesce(confirmed_at, created_at)
WHERE status = 'CONFIRMED';

-- 2c. No row holds 'CONFIRMED' any more, so the union can be narrowed.
ALTER TABLE public.conversation_meeting_locations
    DROP CONSTRAINT IF EXISTS conversation_meeting_locations_status_check;
ALTER TABLE public.conversation_meeting_locations
    ADD CONSTRAINT conversation_meeting_locations_status_check
    CHECK (status IN ('PROPOSED', 'ACCEPTED', 'REJECTED', 'SUPERSEDED'));

-- 2d. The newest accepted row per conversation is the agreed spot. A partial
--     unique index would reject a conversation that somehow has two, so the
--     demotion runs first and the index is added afterwards.
WITH ranked AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY conversation_id
               ORDER BY confirmed_at DESC NULLS LAST, created_at DESC
           ) AS rn
    FROM public.conversation_meeting_locations
    WHERE status = 'ACCEPTED'
)
UPDATE public.conversation_meeting_locations m
SET is_current = true
FROM ranked r
WHERE m.id = r.id AND r.rn = 1;

CREATE UNIQUE INDEX IF NOT EXISTS idx_meeting_locations_one_current
    ON public.conversation_meeting_locations (conversation_id)
    WHERE is_current;

-- All proposals stay in history, so the timeline reads the whole conversation.
-- idx_meeting_locations_conv (conversation_id, created_at DESC) from 006
-- already serves that read; no new index needed.


-- --------------------------------------------------------------------
-- 3. MEETING RESPONSE EVENTS (the centered "Alex accepted the meeting spot."
--    rows). Server-authorized: authenticated gets SELECT and nothing else, so
--    a client can neither forge an event nor claim a response it did not make.
--    UNIQUE(meeting_id) is the retry guard - a proposal can only be answered
--    once, so a replayed request can never append a second event.
-- --------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.meeting_response_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
    meeting_id UUID NOT NULL REFERENCES public.conversation_meeting_locations(id) ON DELETE CASCADE,
    actor_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    response TEXT NOT NULL CHECK (response IN ('ACCEPTED', 'REJECTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now())
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_meeting_response_events_one_per_meeting
    ON public.meeting_response_events (meeting_id);

CREATE INDEX IF NOT EXISTS idx_meeting_response_events_conversation
    ON public.meeting_response_events (conversation_id, created_at ASC);

ALTER TABLE public.meeting_response_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.meeting_response_events FORCE ROW LEVEL SECURITY;

REVOKE ALL ON public.meeting_response_events FROM PUBLIC;
GRANT SELECT ON public.meeting_response_events TO authenticated;

DROP POLICY IF EXISTS "Meeting response events readable by conversation participants" ON public.meeting_response_events;
CREATE POLICY "Meeting response events readable by conversation participants"
    ON public.meeting_response_events FOR SELECT TO authenticated
    USING (
        EXISTS (
            SELECT 1 FROM public.conversations c
            WHERE c.id = meeting_response_events.conversation_id
              AND (c.owner_id = auth.uid() OR c.claimant_id = auth.uid())
        )
    );


-- --------------------------------------------------------------------
-- 4. CLOSE THE DIRECT-WRITE HOLE ON MEETING LOCATIONS
-- 006 granted INSERT and UPDATE to authenticated. Combined with the 006 UPDATE
-- policy (any participant may update any row of their conversation) that let a
-- proposer PATCH their own PROPOSED row to CONFIRMED and self-accept, and let
-- a client write arbitrary status / is_current values directly. All writes now
-- go through the SECURITY DEFINER RPCs in 011, which verify the participant
-- and the responder. Reads are unchanged.
-- --------------------------------------------------------------------
REVOKE INSERT, UPDATE, DELETE ON public.conversation_meeting_locations FROM authenticated;
GRANT SELECT ON public.conversation_meeting_locations TO authenticated;


-- --------------------------------------------------------------------
-- 5. REPLY TARGETS MUST BELONG TO THE SAME CONVERSATION
-- The messages INSERT policy already pins sender_id = auth.uid() and requires
-- conversation membership, so a client cannot forge a sender or post into
-- someone else's thread. This trigger closes the last gap: a reference into a
-- different conversation is rejected instead of silently rendering a quote the
-- reader cannot open.
--
-- SECURITY DEFINER so the existence check is not silently filtered by the
-- caller's RLS - a cross-conversation id must produce this clear error, not a
-- null that reads as "missing".
-- --------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.validate_message_reply_target()
RETURNS TRIGGER AS $$
DECLARE
    v_conversation_id UUID;
BEGIN
    IF NEW.reply_to_message_id IS NOT NULL THEN
        SELECT m.conversation_id INTO v_conversation_id
        FROM public.messages m
        WHERE m.id = NEW.reply_to_message_id;

        IF v_conversation_id IS NULL OR v_conversation_id <> NEW.conversation_id THEN
            RAISE EXCEPTION 'Reply target message does not belong to this conversation';
        END IF;

    ELSIF NEW.reply_to_meeting_id IS NOT NULL THEN
        SELECT ml.conversation_id INTO v_conversation_id
        FROM public.conversation_meeting_locations ml
        WHERE ml.id = NEW.reply_to_meeting_id;

        IF v_conversation_id IS NULL OR v_conversation_id <> NEW.conversation_id THEN
            RAISE EXCEPTION 'Reply target handover location does not belong to this conversation';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

REVOKE EXECUTE ON FUNCTION public.validate_message_reply_target() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.validate_message_reply_target() TO authenticated;

-- Plain BEFORE INSERT OR UPDATE rather than a column-scoped UPDATE OF: nothing
-- in this app updates messages (authenticated has no UPDATE grant), so a
-- column list would narrow the guard for no benefit.
DROP TRIGGER IF EXISTS trg_validate_message_reply_target ON public.messages;
CREATE TRIGGER trg_validate_message_reply_target
    BEFORE INSERT OR UPDATE
    ON public.messages
    FOR EACH ROW
    EXECUTE FUNCTION public.validate_message_reply_target();


-- --------------------------------------------------------------------
-- 6. SANITY CONSTRAINTS
-- An answered row must be timestamped; is_current is only ever true for an
-- accepted row. Both are pure invariants over columns this schema owns, so they
-- are safe to enforce at the storage layer.
--
-- responded_by is deliberately NOT required to be non-null. A pre-010 row whose
-- confirmed_by was never populated must not be able to block this migration, and
-- the app renders an unnamed responder correctly. Everything this app writes
-- always sets it (see 011).
-- --------------------------------------------------------------------
ALTER TABLE public.conversation_meeting_locations
    DROP CONSTRAINT IF EXISTS chk_meeting_locations_answer_consistency;
ALTER TABLE public.conversation_meeting_locations
    ADD CONSTRAINT chk_meeting_locations_answer_consistency
    CHECK (
        (status = 'PROPOSED' AND responded_by IS NULL AND responded_at IS NULL AND is_current = false)
        OR (status IN ('ACCEPTED', 'REJECTED') AND responded_at IS NOT NULL)
        OR (status = 'SUPERSEDED')
    );

ALTER TABLE public.conversation_meeting_locations
    DROP CONSTRAINT IF EXISTS chk_meeting_locations_current_is_accepted;
ALTER TABLE public.conversation_meeting_locations
    ADD CONSTRAINT chk_meeting_locations_current_is_accepted
    CHECK (is_current = false OR status = 'ACCEPTED');

COMMENT ON TABLE public.meeting_response_events IS
    'Server-authorized accept/decline events rendered as centered rows in the chat timeline. One row per proposal (UNIQUE(meeting_id)); created only by respond_to_meeting_proposal.';
COMMENT ON COLUMN public.conversation_meeting_locations.is_current IS
    'True for the single agreed handover location of the conversation. At most one row per conversation (partial unique index). An older accepted proposal keeps status=ACCEPTED with is_current=false, which is how "previously accepted" is told apart from the current location.';
COMMENT ON COLUMN public.messages.reply_to_message_id IS
    'Optional quoted-reply target: an earlier message in the same conversation (enforced by trg_validate_message_reply_target).';
COMMENT ON COLUMN public.messages.reply_to_meeting_id IS
    'Optional quoted-reply target: a meeting proposal in the same conversation (enforced by trg_validate_message_reply_target).';
