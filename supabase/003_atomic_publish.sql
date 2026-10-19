-- Atomic publication of a previously validated and RSA-signed schema-2 envelope.
-- Execute in Supabase SQL Editor. Never grant this RPC to anon/authenticated.
BEGIN;

CREATE OR REPLACE FUNCTION public.publish_admin_draft(
  p_draft_id uuid,
  p_expected_version bigint,
  p_envelope jsonb
) RETURNS bigint
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_draft public.admin_config_drafts%ROWTYPE;
  v_version bigint;
  v_new_version bigint;
BEGIN
  -- The signing service uses service_role only; Android clients cannot call this.
  IF auth.role() IS DISTINCT FROM 'service_role' THEN
    RAISE EXCEPTION 'publisher access denied' USING ERRCODE = '42501';
  END IF;
  IF p_expected_version IS NULL OR p_expected_version < 0 THEN
    RAISE EXCEPTION 'invalid expected version';
  END IF;
  SELECT version INTO v_version
    FROM public.app_config WHERE id = 'main' FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'publication row missing'; END IF;
  IF v_version <> p_expected_version THEN
    RAISE EXCEPTION 'publication version conflict';
  END IF;
  SELECT * INTO v_draft FROM public.admin_config_drafts
    WHERE id = p_draft_id FOR UPDATE;
  IF NOT FOUND OR v_draft.status <> 'pending' THEN
    RAISE EXCEPTION 'draft not pending';
  END IF;
  IF v_draft.base_version <> v_version THEN
    RAISE EXCEPTION 'draft base version conflict';
  END IF;
  v_new_version := v_version + 1;
  IF p_envelope->>'kind' IS DISTINCT FROM 'mazda6gh-published-settings'
     OR p_envelope->>'schema' IS DISTINCT FROM '2'
     OR (p_envelope->>'version')::bigint <> v_new_version
     OR p_envelope->>'signature' IS NULL
     OR length(p_envelope->>'signature') < 100
     OR p_envelope->>'snapshot_payload' IS NULL
     OR length(p_envelope->>'snapshot_payload') > 262144
     OR (p_envelope->>'snapshot_payload')::jsonb <> v_draft.snapshot THEN
    RAISE EXCEPTION 'invalid signed publication envelope';
  END IF;
  UPDATE public.app_config
    SET version = v_new_version, config = p_envelope
    WHERE id = 'main';
  UPDATE public.admin_config_drafts SET status = 'approved'
    WHERE id = p_draft_id;
  RETURN v_new_version;
END;
$$;

REVOKE ALL ON FUNCTION public.publish_admin_draft(uuid,bigint,jsonb) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.publish_admin_draft(uuid,bigint,jsonb) TO service_role;
COMMIT;
