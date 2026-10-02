-- ====================================================================
-- SUPABASE MIGRATION 005: REJECTED CLAIM WORKFLOW & REJECTION MESSAGE
-- ====================================================================

-- 1. Deduplicate any existing claims before adding unique constraint.
-- Keeps the most advanced claim (ACCEPTED > PENDING > REJECTED) or newest per (report_id, claimant_id).
DELETE FROM public.claims c1
WHERE c1.id NOT IN (
    SELECT DISTINCT ON (report_id, claimant_id) id
    FROM public.claims
    ORDER BY report_id, claimant_id,
             CASE status WHEN 'ACCEPTED' THEN 1 WHEN 'PENDING' THEN 2 ELSE 3 END,
             created_at DESC
);

-- 2. Add database-level unique constraint to strictly enforce ONE claim per claimant per report.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'uq_claims_report_claimant'
    ) THEN
        ALTER TABLE public.claims
        ADD CONSTRAINT uq_claims_report_claimant UNIQUE (report_id, claimant_id);
    END IF;
END $$;

-- 3. Add optional rejection_message field to public.claims (up to 500 characters).
ALTER TABLE public.claims
ADD COLUMN IF NOT EXISTS rejection_message TEXT CHECK (
    rejection_message IS NULL OR (char_length(trim(rejection_message)) > 0 AND char_length(rejection_message) <= 500)
);

-- 4. Update protect_claim_fields trigger to prevent direct tampering with rejection_message.
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

        IF NEW.rejection_message IS DISTINCT FROM OLD.rejection_message THEN
            RAISE EXCEPTION 'Modifying rejection message directly is prohibited. Use reject_claim RPC.';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

-- 5. Updated reject_claim RPC with optional message support
CREATE OR REPLACE FUNCTION public.reject_claim(
    p_claim_id UUID,
    p_rejection_message TEXT DEFAULT NULL
)
RETURNS JSON AS $$
DECLARE
    v_user_id UUID;
    v_claim public.claims%ROWTYPE;
    v_report public.reports%ROWTYPE;
    v_trimmed_msg TEXT;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    -- Authorize workflow modifications in this transaction
    PERFORM set_config('app.in_authorized_workflow', 'true', true);

    -- Find target claim
    SELECT * INTO v_claim FROM public.claims WHERE id = p_claim_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Claim not found';
    END IF;

    -- Lock hierarchy: lock report first, then claim
    SELECT * INTO v_report FROM public.reports WHERE id = v_claim.report_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Report not found';
    END IF;

    SELECT * INTO v_claim FROM public.claims WHERE id = p_claim_id FOR UPDATE;

    -- Enforce that caller is the owner of the report
    IF v_report.owner_id <> v_user_id THEN
        RAISE EXCEPTION 'Only the report owner can reject a claim';
    END IF;

    -- Enforce that claim is in PENDING state
    IF v_claim.status <> 'PENDING' THEN
        RAISE EXCEPTION 'Only pending claims can be rejected';
    END IF;

    -- Process optional rejection message
    IF p_rejection_message IS NOT NULL THEN
        v_trimmed_msg := trim(p_rejection_message);
        IF char_length(v_trimmed_msg) = 0 THEN
            v_trimmed_msg := NULL;
        ELSIF char_length(v_trimmed_msg) > 500 THEN
            RAISE EXCEPTION 'Rejection message cannot exceed 500 characters';
        END IF;
    ELSE
        v_trimmed_msg := NULL;
    END IF;

    -- Atomically update claim to REJECTED and set rejection_message
    UPDATE public.claims
    SET status = 'REJECTED',
        rejection_message = v_trimmed_msg,
        reviewed_at = now()
    WHERE id = p_claim_id;

    RETURN json_build_object(
        'success', true,
        'claim_id', p_claim_id,
        'rejection_message', v_trimmed_msg
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

-- 6. Backwards compatibility overload for 1-argument call reject_claim(UUID)
CREATE OR REPLACE FUNCTION public.reject_claim(p_claim_id UUID)
RETURNS JSON AS $$
BEGIN
    RETURN public.reject_claim(p_claim_id, NULL);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

-- Revoke default public execution & grant to authenticated
REVOKE EXECUTE ON FUNCTION public.reject_claim(UUID, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.reject_claim(UUID, TEXT) TO authenticated;

REVOKE EXECUTE ON FUNCTION public.reject_claim(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.reject_claim(UUID) TO authenticated;
