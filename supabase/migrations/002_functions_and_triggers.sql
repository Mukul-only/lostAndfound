-- ====================================================================
-- SUPABASE MIGRATION 002: HARDENED FUNCTIONS, TRIGGERS & WORKFLOW INTEGRITY
-- ====================================================================

-- 1. TRIGGER: PREVENT TAMPERING WITH WORKFLOW FIELDS ON REPORTS
CREATE OR REPLACE FUNCTION public.protect_report_fields()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.owner_id <> OLD.owner_id THEN
        RAISE EXCEPTION 'Modifying report owner is prohibited';
    END IF;

    IF current_setting('app.in_authorized_workflow', true) IS DISTINCT FROM 'true' THEN
        IF NEW.accepted_claim_id IS DISTINCT FROM OLD.accepted_claim_id THEN
            RAISE EXCEPTION 'Modifying accepted_claim_id directly is prohibited. Use accept_claim RPC.';
        END IF;

        IF NEW.accepted_claimant_id IS DISTINCT FROM OLD.accepted_claimant_id THEN
            RAISE EXCEPTION 'Modifying accepted_claimant_id directly is prohibited. Use accept_claim RPC.';
        END IF;

        IF NEW.status <> OLD.status THEN
            RAISE EXCEPTION 'Directly modifying status is prohibited. Use authorized status RPC functions.';
        END IF;
    END IF;

    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS trg_protect_report_fields ON public.reports;
CREATE TRIGGER trg_protect_report_fields
BEFORE UPDATE ON public.reports
FOR EACH ROW EXECUTE FUNCTION public.protect_report_fields();


-- 2. TRIGGER: PREVENT TAMPERING WITH WORKFLOW FIELDS ON CLAIMS
CREATE OR REPLACE FUNCTION public.protect_claim_fields()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.claimant_id <> OLD.claimant_id THEN
        RAISE EXCEPTION 'Modifying claimant identity is prohibited';
    END IF;

    IF NEW.report_id <> OLD.report_id THEN
        RAISE EXCEPTION 'Modifying target report is prohibited';
    END IF;

    IF current_setting('app.in_authorized_workflow', true) IS DISTINCT FROM 'true' THEN
        IF NEW.status <> OLD.status THEN
            RAISE EXCEPTION 'Modifying claim status directly is prohibited. Use accept_claim or reject_claim RPC.';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS trg_protect_claim_fields ON public.claims;
CREATE TRIGGER trg_protect_claim_fields
BEFORE UPDATE ON public.claims
FOR EACH ROW EXECUTE FUNCTION public.protect_claim_fields();


-- 3. TRIGGER: AUTOMATICALLY UPDATE CONVERSATION ON MESSAGE INSERT
CREATE OR REPLACE FUNCTION public.handle_message_insert()
RETURNS TRIGGER AS $$
BEGIN
    UPDATE public.conversations
    SET last_message = NEW.content,
        last_message_at = NEW.created_at,
        last_sender_id = NEW.sender_id
    WHERE id = NEW.conversation_id;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS trg_handle_message_insert ON public.messages;
CREATE TRIGGER trg_handle_message_insert
AFTER INSERT ON public.messages
FOR EACH ROW EXECUTE FUNCTION public.handle_message_insert();


-- 4. RPC: ATOMIC REPORT CREATION WITH PRIVATE FINDER DETAILS
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
    p_incident_time_approx TEXT
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

    -- Insert public report
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


-- 5. RPC: SUBMIT CLAIM (VALIDATES OPEN STATUS, PREVENTS SELF-CLAIM & DUPLICATES)
CREATE OR REPLACE FUNCTION public.submit_claim(
    p_report_id UUID,
    p_note_or_evidence TEXT
)
RETURNS JSON AS $$
DECLARE
    v_user_id UUID;
    v_user_name TEXT;
    v_report public.reports%ROWTYPE;
    v_claim_id UUID;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    IF p_note_or_evidence IS NULL OR char_length(trim(p_note_or_evidence)) < 5 THEN
        RAISE EXCEPTION 'Claim evidence or response note must be at least 5 characters';
    END IF;

    -- Fetch target report
    SELECT * INTO v_report FROM public.reports WHERE id = p_report_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Report not found';
    END IF;

    -- Prevent self claims
    IF v_report.owner_id = v_user_id THEN
        RAISE EXCEPTION 'Cannot claim your own report';
    END IF;

    -- Prevent claims on reports that are not OPEN
    IF v_report.status <> 'OPEN' THEN
        RAISE EXCEPTION 'Cannot claim a report that is not open';
    END IF;

    -- Prevent duplicate submissions by the same user on this report
    IF EXISTS (
        SELECT 1 FROM public.claims WHERE report_id = p_report_id AND claimant_id = v_user_id
    ) THEN
        RAISE EXCEPTION 'You have already submitted a claim for this report';
    END IF;

    -- Get claimant display name
    SELECT full_name INTO v_user_name FROM public.profiles WHERE id = v_user_id;
    IF v_user_name IS NULL OR trim(v_user_name) = '' THEN
        v_user_name := 'Campus Student';
    END IF;

    INSERT INTO public.claims (
        report_id,
        claimant_id,
        claimant_name,
        note_or_evidence,
        status
    ) VALUES (
        p_report_id,
        v_user_id,
        v_user_name,
        p_note_or_evidence,
        'PENDING'
    ) RETURNING id INTO v_claim_id;

    RETURN json_build_object(
        'success', true,
        'claim_id', v_claim_id
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;


-- 6. RPC: ATOMIC CLAIM ACCEPTANCE
CREATE OR REPLACE FUNCTION public.accept_claim(p_claim_id UUID)
RETURNS JSON AS $$
DECLARE
    v_user_id UUID;
    v_claim public.claims%ROWTYPE;
    v_report public.reports%ROWTYPE;
    v_conv_id UUID;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    -- Authorize workflow state modifications within this transaction
    PERFORM set_config('app.in_authorized_workflow', 'true', true);

    -- Consistent lock ordering: find report ID first
    SELECT * INTO v_claim FROM public.claims WHERE id = p_claim_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Claim not found';
    END IF;

    -- Step 1: Lock the report first
    SELECT * INTO v_report FROM public.reports WHERE id = v_claim.report_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Report not found';
    END IF;

    -- Step 2: Lock the claim second
    SELECT * INTO v_claim FROM public.claims WHERE id = p_claim_id FOR UPDATE;

    -- Enforce caller is the report owner
    IF v_report.owner_id <> v_user_id THEN
        RAISE EXCEPTION 'Only the report owner can accept a claim';
    END IF;

    -- Validate report is OPEN and no claim is already accepted
    IF v_report.status <> 'OPEN' OR v_report.accepted_claim_id IS NOT NULL THEN
        RAISE EXCEPTION 'Report is not open or already has an accepted claim';
    END IF;

    -- Validate claim is PENDING
    IF v_claim.status <> 'PENDING' THEN
        RAISE EXCEPTION 'Only pending claims can be accepted';
    END IF;

    -- Temporarily disable protect triggers inside atomic RPC
    -- (Triggers are session-wide or we update directly via internal variables)
    -- Mark selected claim as ACCEPTED
    UPDATE public.claims
    SET status = 'ACCEPTED', reviewed_at = now()
    WHERE id = p_claim_id;

    -- Mark all other pending claims on this report as REJECTED
    UPDATE public.claims
    SET status = 'REJECTED', reviewed_at = now()
    WHERE report_id = v_report.id AND id <> p_claim_id AND status = 'PENDING';

    -- Update report state to HANDOVER_ARRANGED
    UPDATE public.reports
    SET status = 'HANDOVER_ARRANGED',
        accepted_claim_id = p_claim_id,
        accepted_claimant_id = v_claim.claimant_id,
        updated_at = now()
    WHERE id = v_report.id;

    -- Create or retrieve conversation atomically
    INSERT INTO public.conversations (
        report_id,
        claim_id,
        owner_id,
        claimant_id,
        report_title
    ) VALUES (
        v_report.id,
        p_claim_id,
        v_report.owner_id,
        v_claim.claimant_id,
        v_report.title
    )
    ON CONFLICT (claim_id) DO UPDATE SET report_title = EXCLUDED.report_title
    RETURNING id INTO v_conv_id;

    RETURN json_build_object(
        'success', true,
        'conversation_id', v_conv_id,
        'claim_id', p_claim_id,
        'report_id', v_report.id
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;


-- 7. RPC: CLAIM REJECTION
CREATE OR REPLACE FUNCTION public.reject_claim(p_claim_id UUID)
RETURNS JSON AS $$
DECLARE
    v_user_id UUID;
    v_claim public.claims%ROWTYPE;
    v_report public.reports%ROWTYPE;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    PERFORM set_config('app.in_authorized_workflow', 'true', true);

    SELECT * INTO v_claim FROM public.claims WHERE id = p_claim_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Claim not found';
    END IF;

    SELECT * INTO v_report FROM public.reports WHERE id = v_claim.report_id FOR UPDATE;
    SELECT * INTO v_claim FROM public.claims WHERE id = p_claim_id FOR UPDATE;

    IF v_report.owner_id <> v_user_id THEN
        RAISE EXCEPTION 'Only the report owner can reject a claim';
    END IF;

    IF v_claim.status <> 'PENDING' THEN
        RAISE EXCEPTION 'Only pending claims can be rejected';
    END IF;

    UPDATE public.claims
    SET status = 'REJECTED', reviewed_at = now()
    WHERE id = p_claim_id;

    RETURN json_build_object('success', true, 'claim_id', p_claim_id);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;


-- 8. RPC: STATUS TRANSITIONS (RETURNED & CLOSED)
CREATE OR REPLACE FUNCTION public.mark_report_returned(p_report_id UUID)
RETURNS JSON AS $$
DECLARE
    v_user_id UUID;
    v_report public.reports%ROWTYPE;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    PERFORM set_config('app.in_authorized_workflow', 'true', true);

    SELECT * INTO v_report FROM public.reports WHERE id = p_report_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Report not found';
    END IF;

    IF v_report.owner_id <> v_user_id THEN
        RAISE EXCEPTION 'Only the report owner can mark report as returned';
    END IF;

    IF v_report.status NOT IN ('OPEN', 'HANDOVER_ARRANGED') THEN
        RAISE EXCEPTION 'Report cannot be marked returned from status %', v_report.status;
    END IF;

    UPDATE public.reports
    SET status = 'RETURNED', updated_at = now()
    WHERE id = p_report_id;

    RETURN json_build_object('success', true, 'status', 'RETURNED');
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;


CREATE OR REPLACE FUNCTION public.close_report(p_report_id UUID)
RETURNS JSON AS $$
DECLARE
    v_user_id UUID;
    v_report public.reports%ROWTYPE;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    PERFORM set_config('app.in_authorized_workflow', 'true', true);

    SELECT * INTO v_report FROM public.reports WHERE id = p_report_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Report not found';
    END IF;

    IF v_report.owner_id <> v_user_id THEN
        RAISE EXCEPTION 'Only the report owner can close a report';
    END IF;

    UPDATE public.reports
    SET status = 'CLOSED', updated_at = now()
    WHERE id = p_report_id;

    RETURN json_build_object('success', true, 'status', 'CLOSED');
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;


-- ====================================================================
-- HARDEN SECURITY DEFINER EXECUTION PRIVILEGES
-- ====================================================================
REVOKE EXECUTE ON FUNCTION public.protect_report_fields() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.protect_claim_fields() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.handle_message_insert() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.create_report_with_private_details(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, DATE, TEXT) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.submit_claim(UUID, TEXT) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.accept_claim(UUID) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.reject_claim(UUID) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.mark_report_returned(UUID) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.close_report(UUID) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.create_report_with_private_details(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, DATE, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.submit_claim(UUID, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.accept_claim(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.reject_claim(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.mark_report_returned(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.close_report(UUID) TO authenticated;
