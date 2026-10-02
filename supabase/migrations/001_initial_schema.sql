-- ====================================================================
-- SUPABASE MIGRATION 001: CORE TABLES, ENUMS, AND CONSTRAINTS
-- ====================================================================

-- 1. PROFILES TABLE
-- Notice: Student email is NOT stored in profiles to protect student privacy.
-- Public profile lookups only expose full_name. Auth user email remains private in auth.users.
CREATE TABLE IF NOT EXISTS public.profiles (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    full_name TEXT NOT NULL CHECK (char_length(trim(full_name)) >= 2),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 2. REPORTS TABLE (Publicly browsable fields only)
CREATE TABLE IF NOT EXISTS public.reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type TEXT NOT NULL CHECK (type IN ('LOST', 'FOUND')),
    title TEXT NOT NULL CHECK (char_length(trim(title)) >= 3),
    category TEXT NOT NULL CHECK (category IN (
        'ELECTRONICS', 'CARDS_ID', 'KEYS', 'BAGS_WALLETS', 'CLOTHING', 'BOOKS_STATIONERY', 'OTHER'
    )),
    description TEXT NOT NULL CHECK (char_length(trim(description)) >= 5),
    public_verification_question TEXT, -- Only for FOUND items, prompts potential owners publicly
    image_url TEXT,
    campus_location TEXT NOT NULL CHECK (char_length(trim(campus_location)) >= 2),
    incident_date DATE NOT NULL,
    incident_time_approx TEXT,
    status TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'HANDOVER_ARRANGED', 'RETURNED', 'CLOSED')),
    owner_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    owner_name TEXT NOT NULL,
    accepted_claim_id UUID, -- References claims(id)
    accepted_claimant_id UUID REFERENCES auth.users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 3. REPORT PRIVATE DETAILS (Finder secret identifying info)
-- Kept separate so public report queries NEVER leak private verification notes
CREATE TABLE IF NOT EXISTS public.report_private_details (
    report_id UUID PRIMARY KEY REFERENCES public.reports(id) ON DELETE CASCADE,
    finder_private_notes TEXT NOT NULL CHECK (char_length(trim(finder_private_notes)) >= 3),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 4. CLAIMS TABLE
CREATE TABLE IF NOT EXISTS public.claims (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    report_id UUID NOT NULL REFERENCES public.reports(id) ON DELETE CASCADE,
    claimant_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    claimant_name TEXT NOT NULL,
    note_or_evidence TEXT NOT NULL CHECK (char_length(trim(note_or_evidence)) >= 5),
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at TIMESTAMPTZ
);

-- Add foreign key constraint from reports.accepted_claim_id to claims(id)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_reports_accepted_claim'
    ) THEN
        ALTER TABLE public.reports
            ADD CONSTRAINT fk_reports_accepted_claim
            FOREIGN KEY (accepted_claim_id) REFERENCES public.claims(id) ON DELETE SET NULL;
    END IF;
END $$;

-- 5. CONVERSATIONS TABLE
CREATE TABLE IF NOT EXISTS public.conversations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    report_id UUID NOT NULL REFERENCES public.reports(id) ON DELETE CASCADE,
    claim_id UUID NOT NULL REFERENCES public.claims(id) ON DELETE CASCADE UNIQUE,
    owner_id UUID NOT NULL REFERENCES auth.users(id),
    claimant_id UUID NOT NULL REFERENCES auth.users(id),
    report_title TEXT NOT NULL,
    last_message TEXT,
    last_message_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_sender_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 6. MESSAGES TABLE
CREATE TABLE IF NOT EXISTS public.messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
    sender_id UUID NOT NULL REFERENCES auth.users(id),
    sender_name TEXT NOT NULL,
    content TEXT NOT NULL CHECK (char_length(trim(content)) >= 1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 7. ABUSE REPORTS TABLE
CREATE TABLE IF NOT EXISTS public.abuse_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_id UUID NOT NULL REFERENCES auth.users(id),
    target_report_id UUID REFERENCES public.reports(id) ON DELETE SET NULL,
    target_user_id UUID REFERENCES auth.users(id) ON DELETE SET NULL,
    reason TEXT NOT NULL CHECK (reason IN ('SPAM', 'SCAM_FRAUD', 'HARASSMENT', 'INAPPROPRIATE_CONTENT', 'OTHER')),
    details TEXT NOT NULL CHECK (char_length(trim(details)) >= 5),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ====================================================================
-- INDEXES & INTEGRITY CONSTRAINTS
-- ====================================================================

CREATE INDEX IF NOT EXISTS idx_reports_type_status ON public.reports(type, status);
CREATE INDEX IF NOT EXISTS idx_reports_category ON public.reports(category);
CREATE INDEX IF NOT EXISTS idx_reports_campus_location ON public.reports(campus_location);
CREATE INDEX IF NOT EXISTS idx_reports_owner ON public.reports(owner_id);
CREATE INDEX IF NOT EXISTS idx_claims_report ON public.claims(report_id);
CREATE INDEX IF NOT EXISTS idx_claims_claimant ON public.claims(claimant_id);
CREATE INDEX IF NOT EXISTS idx_conversations_participants ON public.conversations(owner_id, claimant_id);
CREATE INDEX IF NOT EXISTS idx_messages_conversation ON public.messages(conversation_id, created_at ASC);

-- Enforce exactly one claim per user per report across all statuses (prevents spam/harassment after rejection)
CREATE UNIQUE INDEX IF NOT EXISTS idx_unique_claim_per_user_report
ON public.claims (report_id, claimant_id);

-- Enforce at most one ACCEPTED claim per report at database level
CREATE UNIQUE INDEX IF NOT EXISTS idx_unique_accepted_claim_per_report
ON public.claims (report_id)
WHERE (status = 'ACCEPTED');
