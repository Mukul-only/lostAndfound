-- 013_drop_stale_report_rpc_overloads.sql
--
-- Fixes PGRST203 on create_report_with_private_details.
--
-- WHY THIS EXISTS
--   Migrations 002, 006, 007 and 008 each declared
--   create_report_with_private_details, but NOT with the same argument order:
--
--     002      (TEXT x8, DATE, TEXT)                        -- 10 params
--     006/007  (TEXT x6, TEXT, TEXT, DATE, TEXT, DOUBLE x2) -- 12 params
--     008      (TEXT x6, DATE, TEXT, TEXT, TEXT, DOUBLE x2) -- 12 params
--
--   Postgres identifies a function by its full argument TYPE SEQUENCE, not by
--   name. 008 only moved p_image_url ahead of p_public_verification_question,
--   which shifts DATE to a different position and therefore produces a
--   *different* signature. CREATE OR REPLACE cannot replace a function whose
--   signature changed -- it silently creates an additional overload.
--
--   Result: two 12-parameter overloads coexisted, both of which accept every
--   key the Android client sends, so PostgREST could not choose between them
--   and returned PGRST203 ("Could not choose the best candidate function").
--
-- WHAT THIS DOES
--   Drops the superseded overloads and keeps migration 008's version as the
--   single public entry point. 008's signature is the one the app calls.
--
--   Run AFTER 008. This is also the point to reload the PostgREST schema
--   cache, which is why the NOTIFY at the end matters.

-- 1. Migration 006/007's 12-parameter overload (the one causing PGRST203).
DROP FUNCTION IF EXISTS public.create_report_with_private_details(
    TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, DATE, TEXT,
    DOUBLE PRECISION, DOUBLE PRECISION
);

-- 2. Migration 002's original 10-parameter overload, superseded since 006.
--    Kept here so the name resolves to exactly one function.
DROP FUNCTION IF EXISTS public.create_report_with_private_details(
    TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, DATE, TEXT
);

-- 3. Re-assert the execute grants on the surviving signature. The drops above
--    cannot affect it, but stating them here keeps this migration
--    self-contained and safe to run more than once.
REVOKE EXECUTE ON FUNCTION public.create_report_with_private_details(
    TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, DATE, TEXT, TEXT, TEXT,
    DOUBLE PRECISION, DOUBLE PRECISION
) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.create_report_with_private_details(
    TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, DATE, TEXT, TEXT, TEXT,
    DOUBLE PRECISION, DOUBLE PRECISION
) TO authenticated;

-- 4. Guard: exactly one create_report_with_private_details must remain.
--    Fail loudly rather than leave a latent PGRST203 behind.
DO $$
DECLARE
    n integer;
BEGIN
    SELECT count(*) INTO n
    FROM pg_proc p
    JOIN pg_namespace ns ON ns.oid = p.pronamespace
    WHERE ns.nspname = 'public'
      AND p.proname = 'create_report_with_private_details';

    IF n <> 1 THEN
        RAISE EXCEPTION
            'Expected exactly 1 create_report_with_private_details, found % -- '
            'an overload survived and PostgREST will still return PGRST203', n;
    END IF;
END $$;

-- 5. Reload PostgREST's schema cache so the surviving signature is the one it
--    resolves, and so functions added after the last reload become callable.
NOTIFY pgrst, 'reload schema';