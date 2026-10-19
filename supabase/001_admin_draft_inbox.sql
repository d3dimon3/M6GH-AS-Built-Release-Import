-- Supabase migration: safe draft inbox (NOT the public signed publication).
-- Apply manually in Supabase SQL Editor after reviewing admin UUID.
-- No existing app_config data or policies are modified.
BEGIN;

CREATE TABLE IF NOT EXISTS public.admin_config_drafts (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_uid uuid NOT NULL DEFAULT auth.uid(),
  base_version bigint NOT NULL CHECK (base_version >= 0),
  snapshot jsonb NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
  status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','approved','rejected')),
  created_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE public.admin_config_drafts ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON public.admin_config_drafts FROM PUBLIC, anon, authenticated;
GRANT SELECT, INSERT ON public.admin_config_drafts TO authenticated;

-- Replace this UID only if the Supabase administrator account changes.
DROP POLICY IF EXISTS admin_drafts_insert ON public.admin_config_drafts;
CREATE POLICY admin_drafts_insert ON public.admin_config_drafts
  FOR INSERT TO authenticated
  WITH CHECK (
    auth.uid() = 'b1c01dc8-bebf-45a5-8e60-e9b5e0870e37'::uuid
    AND owner_uid = auth.uid()
    AND status = 'pending'
    AND base_version = COALESCE(
      (SELECT version::bigint FROM public.app_config WHERE id = 'main'), 0
    )
  );

DROP POLICY IF EXISTS admin_drafts_read ON public.admin_config_drafts;
CREATE POLICY admin_drafts_read ON public.admin_config_drafts
  FOR SELECT TO authenticated
  USING (
    auth.uid() = 'b1c01dc8-bebf-45a5-8e60-e9b5e0870e37'::uuid
    AND owner_uid = auth.uid()
  );

-- No UPDATE or DELETE privilege/policy for Android clients.
-- No INSERT/UPDATE privilege to the public app_config publication is added.
COMMIT;

-- Next phase: a trusted publisher validates pending drafts, signs with
-- the existing RSA publisher key, and writes a full signed envelope into
-- app_config.config while atomically advancing app_config.version.
-- NEVER place the private signing key in SQL, an APK, or this repository.
