-- ====================================================================
-- SUPABASE MIGRATION 003: ROW LEVEL SECURITY POLICIES & PERMISSIONS
-- ====================================================================

-- Enable RLS on all tables and force it so it cannot be bypassed
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.profiles FORCE ROW LEVEL SECURITY;
ALTER TABLE public.reports ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.reports FORCE ROW LEVEL SECURITY;
ALTER TABLE public.report_private_details ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.report_private_details FORCE ROW LEVEL SECURITY;
ALTER TABLE public.claims ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.claims FORCE ROW LEVEL SECURITY;
ALTER TABLE public.conversations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.conversations FORCE ROW LEVEL SECURITY;
ALTER TABLE public.messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.messages FORCE ROW LEVEL SECURITY;
ALTER TABLE public.abuse_reports ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.abuse_reports FORCE ROW LEVEL SECURITY;

-- Revoke default PUBLIC table access
REVOKE ALL ON public.profiles FROM PUBLIC;
REVOKE ALL ON public.reports FROM PUBLIC;
REVOKE ALL ON public.report_private_details FROM PUBLIC;
REVOKE ALL ON public.claims FROM PUBLIC;
REVOKE ALL ON public.conversations FROM PUBLIC;
REVOKE ALL ON public.messages FROM PUBLIC;
REVOKE ALL ON public.abuse_reports FROM PUBLIC;

-- Grant selective basic permissions to authenticated role
GRANT SELECT, INSERT, UPDATE ON public.profiles TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.reports TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.report_private_details TO authenticated;
GRANT SELECT, INSERT ON public.claims TO authenticated;
GRANT SELECT ON public.conversations TO authenticated;
GRANT SELECT, INSERT ON public.messages TO authenticated;
GRANT INSERT ON public.abuse_reports TO authenticated;


-- --------------------------------------------------------------------
-- 1. PROFILES POLICIES
-- --------------------------------------------------------------------
DROP POLICY IF EXISTS "Profiles readable by authenticated students" ON public.profiles;
CREATE POLICY "Profiles readable by authenticated students"
ON public.profiles FOR SELECT TO authenticated
USING (true);

DROP POLICY IF EXISTS "Users can insert their own profile" ON public.profiles;
CREATE POLICY "Users can insert their own profile"
ON public.profiles FOR INSERT TO authenticated
WITH CHECK (auth.uid() = id);

DROP POLICY IF EXISTS "Users can update their own profile" ON public.profiles;
CREATE POLICY "Users can update their own profile"
ON public.profiles FOR UPDATE TO authenticated
USING (auth.uid() = id);


-- --------------------------------------------------------------------
-- 2. REPORTS POLICIES
-- --------------------------------------------------------------------
DROP POLICY IF EXISTS "Public reports viewable by authenticated students" ON public.reports;
CREATE POLICY "Public reports viewable by authenticated students"
ON public.reports FOR SELECT TO authenticated
USING (true);

DROP POLICY IF EXISTS "Users can insert their own reports" ON public.reports;
CREATE POLICY "Users can insert their own reports"
ON public.reports FOR INSERT TO authenticated
WITH CHECK (auth.uid() = owner_id);

DROP POLICY IF EXISTS "Owners can update their own reports" ON public.reports;
CREATE POLICY "Owners can update their own reports"
ON public.reports FOR UPDATE TO authenticated
USING (auth.uid() = owner_id);

DROP POLICY IF EXISTS "Owners can delete their own reports" ON public.reports;
CREATE POLICY "Owners can delete their own reports"
ON public.reports FOR DELETE TO authenticated
USING (auth.uid() = owner_id);


-- --------------------------------------------------------------------
-- 3. REPORT PRIVATE DETAILS (Finder secret note)
-- Strictly accessible ONLY to the owner of the report!
-- --------------------------------------------------------------------
DROP POLICY IF EXISTS "Owners can view private details" ON public.report_private_details;
CREATE POLICY "Owners can view private details"
ON public.report_private_details FOR SELECT TO authenticated
USING (
    EXISTS (
        SELECT 1 FROM public.reports
        WHERE reports.id = report_private_details.report_id
          AND reports.owner_id = auth.uid()
    )
);

DROP POLICY IF EXISTS "Owners can insert private details" ON public.report_private_details;
CREATE POLICY "Owners can insert private details"
ON public.report_private_details FOR INSERT TO authenticated
WITH CHECK (
    EXISTS (
        SELECT 1 FROM public.reports
        WHERE reports.id = report_private_details.report_id
          AND reports.owner_id = auth.uid()
    )
);

DROP POLICY IF EXISTS "Owners can update private details" ON public.report_private_details;
CREATE POLICY "Owners can update private details"
ON public.report_private_details FOR UPDATE TO authenticated
USING (
    EXISTS (
        SELECT 1 FROM public.reports
        WHERE reports.id = report_private_details.report_id
          AND reports.owner_id = auth.uid()
    )
);


-- --------------------------------------------------------------------
-- 4. CLAIMS POLICIES
-- Only the claimant AND the report owner can view a claim
-- --------------------------------------------------------------------
DROP POLICY IF EXISTS "Claimant and report owner can view claims" ON public.claims;
CREATE POLICY "Claimant and report owner can view claims"
ON public.claims FOR SELECT TO authenticated
USING (
    claimant_id = auth.uid()
    OR EXISTS (
        SELECT 1 FROM public.reports
        WHERE reports.id = claims.report_id
          AND reports.owner_id = auth.uid()
    )
);

DROP POLICY IF EXISTS "Users can insert claims for other reports" ON public.claims;
CREATE POLICY "Users can insert claims for other reports"
ON public.claims FOR INSERT TO authenticated
WITH CHECK (
    claimant_id = auth.uid()
    AND EXISTS (
        SELECT 1 FROM public.reports
        WHERE reports.id = claims.report_id
          AND reports.owner_id <> auth.uid()
          AND reports.status = 'OPEN'
    )
);


-- --------------------------------------------------------------------
-- 5. CONVERSATIONS POLICIES
-- Strictly restricted to owner and accepted claimant
-- Direct INSERT is NOT granted to authenticated; created via accept_claim
-- --------------------------------------------------------------------
DROP POLICY IF EXISTS "Participants can view conversations" ON public.conversations;
CREATE POLICY "Participants can view conversations"
ON public.conversations FOR SELECT TO authenticated
USING (auth.uid() = owner_id OR auth.uid() = claimant_id);


-- --------------------------------------------------------------------
-- 6. MESSAGES POLICIES
-- Only conversation participants can view and post messages
-- --------------------------------------------------------------------
DROP POLICY IF EXISTS "Participants can view messages" ON public.messages;
CREATE POLICY "Participants can view messages"
ON public.messages FOR SELECT TO authenticated
USING (
    EXISTS (
        SELECT 1 FROM public.conversations
        WHERE conversations.id = messages.conversation_id
          AND (conversations.owner_id = auth.uid() OR conversations.claimant_id = auth.uid())
    )
);

DROP POLICY IF EXISTS "Participants can send messages" ON public.messages;
CREATE POLICY "Participants can send messages"
ON public.messages FOR INSERT TO authenticated
WITH CHECK (
    sender_id = auth.uid()
    AND EXISTS (
        SELECT 1 FROM public.conversations
        WHERE conversations.id = messages.conversation_id
          AND (conversations.owner_id = auth.uid() OR conversations.claimant_id = auth.uid())
    )
);


-- --------------------------------------------------------------------
-- 7. ABUSE REPORTS POLICIES
-- Authenticated users may insert reports, but cannot read or update them
-- --------------------------------------------------------------------
DROP POLICY IF EXISTS "Users can report abuse" ON public.abuse_reports;
CREATE POLICY "Users can report abuse"
ON public.abuse_reports FOR INSERT TO authenticated
WITH CHECK (reporter_id = auth.uid());
