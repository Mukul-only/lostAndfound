-- ====================================================================
-- SUPABASE MIGRATION 012: PER-USER READ STATE AND UNREAD COUNTS
-- ====================================================================
-- Incremental only. Nothing in 001-011 is rewritten and no existing row is
-- deleted. Adds one table and three RPCs.
--
-- WHY A CURSOR RATHER THAN A PER-MESSAGE is_read FLAG
-- A boolean per message would mean rewriting history to mark it read, and would
-- make "how far have I read" impossible to express atomically when messages
-- arrive while you are reading. A per-user, per-conversation high-water mark of
-- what has been SEEN is the same idea WhatsApp-style clients use: it is one row
-- per (conversation, user), it advances monotonically, and it never marks an
-- older message unread when a newer one is read.
--
-- WHAT COUNTS AS UNREAD (the rule the client and the RPC must agree on)
-- An item is unread for user U in conversation C when BOTH hold:
--   1. item.created_at > U's read cursor for C, AND
--   2. the item's author is not U.
-- Author is sender_id for a text message, proposer_id for a meeting proposal,
-- and actor_id for a meeting response event. Rule 2 is why your own sends never
-- bump your own badge.
-- The three item kinds live in three separate tables with disjoint UUIDs, so
-- counting them with UNION ALL cannot count one interaction twice.
--
-- SAFETY WHEN MESSAGES ARRIVE WHILE YOU READ
-- mark_conversation_read takes the timestamp the client has actually RENDERED
-- and stores GREATEST(existing, that). A message that lands after that instant
-- has a later created_at, so the cursor stops short of it and it stays unread.
-- The cursor can never move backwards, and the value is clamped to now() so a
-- client cannot post a future instant and silently mute its own badge forever.
--
-- MIGRATION STRATEGY
-- Existing conversations get a cursor seeded to the newest item that already
-- exists. Pre-upgrade history therefore does NOT appear as unread; only content
-- that arrives after the migration is counted. No message or meeting row is
-- touched.
-- ====================================================================


-- --------------------------------------------------------------------
-- 1. READ CURSOR, ONE ROW PER (CONVERSATION, USER)
-- --------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.conversation_read_state (
    conversation_id UUID NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    last_read_at TIMESTAMPTZ NOT NULL DEFAULT '-infinity'::timestamptz,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now()),
    PRIMARY KEY (conversation_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_conversation_read_state_user
    ON public.conversation_read_state (user_id);

ALTER TABLE public.conversation_read_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.conversation_read_state FORCE ROW LEVEL SECURITY;

REVOKE ALL ON public.conversation_read_state FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON public.conversation_read_state TO authenticated;

-- A participant may read their own cursor and move their own cursor. Nothing
-- else: no other user's row is visible, writable, or even countable. The write
-- policy pins user_id to auth.uid(), so a client cannot mark someone else read.
DROP POLICY IF EXISTS "Users can view their own read state" ON public.conversation_read_state;
CREATE POLICY "Users can view their own read state"
    ON public.conversation_read_state FOR SELECT TO authenticated
    USING (user_id = auth.uid());

DROP POLICY IF EXISTS "Users can insert their own read state" ON public.conversation_read_state;
CREATE POLICY "Users can insert their own read state"
    ON public.conversation_read_state FOR INSERT TO authenticated
    WITH CHECK (user_id = auth.uid());

DROP POLICY IF EXISTS "Users can update their own read state" ON public.conversation_read_state;
CREATE POLICY "Users can update their own read state"
    ON public.conversation_read_state FOR UPDATE TO authenticated
    USING (user_id = auth.uid())
    WITH CHECK (user_id = auth.uid());


-- --------------------------------------------------------------------
-- 2. SEED EXISTING CONVERSATIONS
--    The cursor is set to the newest item already in the thread, for BOTH
--    participants, so installing this migration does not light up every past
--    message as unread. ON CONFLICT makes a re-run a no-op.
-- --------------------------------------------------------------------
INSERT INTO public.conversation_read_state (conversation_id, user_id, last_read_at)
SELECT
    c.id,
    c.owner_id,
    GREATEST(
        c.created_at,
        COALESCE((SELECT max(m.created_at) FROM public.messages m
                  WHERE m.conversation_id = c.id), c.created_at),
        COALESCE((SELECT max(ml.created_at) FROM public.conversation_meeting_locations ml
                  WHERE ml.conversation_id = c.id), c.created_at),
        COALESCE((SELECT max(ev.created_at) FROM public.meeting_response_events ev
                  WHERE ev.conversation_id = c.id), c.created_at)
    )
FROM public.conversations c
ON CONFLICT (conversation_id, user_id) DO NOTHING;

INSERT INTO public.conversation_read_state (conversation_id, user_id, last_read_at)
SELECT
    c.id,
    c.claimant_id,
    GREATEST(
        c.created_at,
        COALESCE((SELECT max(m.created_at) FROM public.messages m
                  WHERE m.conversation_id = c.id), c.created_at),
        COALESCE((SELECT max(ml.created_at) FROM public.conversation_meeting_locations ml
                  WHERE ml.conversation_id = c.id), c.created_at),
        COALESCE((SELECT max(ev.created_at) FROM public.meeting_response_events ev
                  WHERE ev.conversation_id = c.id), c.created_at)
    )
FROM public.conversations c
ON CONFLICT (conversation_id, user_id) DO NOTHING;


-- --------------------------------------------------------------------
-- 3. SHARED COUNTING HELPER
--    One definition of "unread" for both RPCs below, so the nav total and the
--    per-row badge can never disagree.
-- --------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.count_unread_for_conversation(
    p_conversation_id UUID,
    p_user_id UUID
)
RETURNS INT
LANGUAGE sql STABLE
SET search_path = public, pg_temp
AS $$
    WITH cursor AS (
        SELECT COALESCE(
            (SELECT r.last_read_at FROM public.conversation_read_state r
              WHERE r.conversation_id = p_conversation_id
                AND r.user_id = p_user_id),
            '-infinity'::timestamptz
        ) AS last_read_at
    ), unread AS (
        -- Incoming text.
        SELECT m.created_at
        FROM public.messages m, cursor c
        WHERE m.conversation_id = p_conversation_id
          AND m.sender_id <> p_user_id
          AND m.created_at > c.last_read_at
        UNION ALL
        -- Meeting proposals from the other participant. My own proposals are
        -- mine, not news to me.
        SELECT ml.created_at
        FROM public.conversation_meeting_locations ml, cursor c
        WHERE ml.conversation_id = p_conversation_id
          AND ml.proposer_id <> p_user_id
          AND ml.created_at > c.last_read_at
        UNION ALL
        -- Accept/decline events raised by the other participant. Events I caused
        -- are not unread for me.
        SELECT ev.created_at
        FROM public.meeting_response_events ev, cursor c
        WHERE ev.conversation_id = p_conversation_id
          AND ev.actor_id <> p_user_id
          AND ev.created_at > c.last_read_at
    )
    SELECT count(*)::INT FROM unread;
$$;

REVOKE EXECUTE ON FUNCTION public.count_unread_for_conversation(UUID, UUID) FROM PUBLIC;


-- --------------------------------------------------------------------
-- 4. UNREAD COUNT PER CONVERSATION, FOR THE CALLER ONLY
--    Returns the caller's own conversations that have something unread.
--    Conversations with a zero count are simply absent, which is what makes the
--    badge disappear rather than showing a 0.
-- --------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.get_conversation_unread_counts()
RETURNS TABLE (conversation_id UUID, unread_count INT)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    RETURN QUERY
    WITH mine AS (
        SELECT c.id AS conv_id
        FROM public.conversations c
        WHERE c.owner_id = v_user_id OR c.claimant_id = v_user_id
    )
    SELECT mine.conv_id,
           public.count_unread_for_conversation(mine.conv_id, v_user_id)
    FROM mine
    WHERE public.count_unread_for_conversation(mine.conv_id, v_user_id) > 0;
END;
$$;

REVOKE EXECUTE ON FUNCTION public.get_conversation_unread_counts() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_conversation_unread_counts() TO authenticated;


-- --------------------------------------------------------------------
-- 5. TOTAL UNREAD, FOR THE BOTTOM NAV BADGE
-- --------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.get_unread_total()
RETURNS INT
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID;
    v_total INT;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    SELECT COALESCE(sum(public.count_unread_for_conversation(c.id, v_user_id)), 0)::INT
    INTO v_total
    FROM public.conversations c
    WHERE c.owner_id = v_user_id OR c.claimant_id = v_user_id;

    RETURN v_total;
END;
$$;

REVOKE EXECUTE ON FUNCTION public.get_unread_total() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_unread_total() TO authenticated;


-- --------------------------------------------------------------------
-- 6. ADVANCE THE READ CURSOR
--    Called only when incoming content has actually been seen in the
--    foreground. Monotonic, clamped to now(), and restricted to a
--    conversation the caller is a participant in.
-- --------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.mark_conversation_read(
    p_conversation_id UUID,
    p_read_at TIMESTAMPTZ
)
RETURNS TIMESTAMPTZ
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID;
    v_conv public.conversations%ROWTYPE;
    v_stored TIMESTAMPTZ;
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
        RAISE EXCEPTION 'Only conversation participants can mark a conversation read';
    END IF;

    IF p_read_at IS NULL THEN
        RAISE EXCEPTION 'read timestamp is required';
    END IF;

    -- Never trust a client to move its own cursor past the present, and never let
    -- it travel backwards. GREATEST is what makes a late or duplicated request
    -- harmless: a replay cannot rewind, and a stale value cannot lower the mark.
    v_stored := LEAST(GREATEST(p_read_at, '-infinity'::timestamptz),
                       timezone('utc'::text, now()));

    INSERT INTO public.conversation_read_state (conversation_id, user_id, last_read_at, updated_at)
    VALUES (p_conversation_id, v_user_id, v_stored, timezone('utc'::text, now()))
    ON CONFLICT (conversation_id, user_id) DO UPDATE
        SET last_read_at = GREATEST(
                public.conversation_read_state.last_read_at, EXCLUDED.last_read_at),
            updated_at = timezone('utc'::text, now())
    RETURNING last_read_at INTO v_stored;

    RETURN v_stored;
END;
$$;

REVOKE EXECUTE ON FUNCTION public.mark_conversation_read(UUID, TIMESTAMPTZ) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mark_conversation_read(UUID, TIMESTAMPTZ) TO authenticated;


COMMENT ON TABLE public.conversation_read_state IS
    'Per-user, per-conversation high-water mark of what has been seen. An item is unread when its created_at is greater than last_read_at and its author is not this user. Rows are seeded at migration time to the newest existing item so past history is not counted as unread.';
COMMENT ON FUNCTION public.mark_conversation_read(UUID, TIMESTAMPTZ) IS
    'Advances the caller''s read cursor for one conversation. Monotonic (GREATEST) and clamped to now(), so a replay or a stale timestamp cannot rewind or overshoot.';