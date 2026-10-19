-- Safe diagnostic: show pending drafts and published version.
-- Read-only. Does not publish, sign, modify, or delete anything.
SELECT
  d.id,
  d.created_at,
  d.status,
  d.base_version,
  p.version AS current_public_version,
  (d.base_version = p.version) AS based_on_current_version,
  d.snapshot->>'kind' AS snapshot_kind,
  d.snapshot->>'schema' AS snapshot_schema,
  jsonb_array_length(
    COALESCE(
      (d.snapshot->'stores'->'run35_settings'->>'features')::jsonb,
      '[]'::jsonb
    )
  ) AS feature_count
FROM public.admin_config_drafts AS d
CROSS JOIN public.app_config AS p
WHERE p.id = 'main'
ORDER BY d.created_at DESC
LIMIT 20;
