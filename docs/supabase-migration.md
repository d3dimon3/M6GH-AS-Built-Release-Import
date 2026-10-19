# Supabase migration — implementation guardrails

Branch: `supabase-sync-migration` (working release branch `release-post476` remains unchanged).

## Verified existing behavior
- `MainActivity` publishes via `GitHubSnapshotUploader.upload` then `requestRelease`.
- `AdminOnlineRestore` downloads GitHub publication and checks `CatalogSignature.verifyFull`.
- `FullCatalogEnvelope` requires `schema=2`, `kind=mazda6gh-published-settings`, `version`, `snapshot_payload`, `signature`.
- `PublishedFullSettings` reads only cached signature-verified settings.
- Supabase `public.app_config` currently contains `id=main`, `config={}`, `version=1`. This is **not** a valid signed publication. Never feed it directly into the Android configuration parser.

## Migration contract
1. Preserve RSA signature verification in Config and Admin download paths; do not disable or bypass `CatalogSignature.verifyFull`.
2. Supabase stores the **entire signed publication envelope**, not an unsigned settings snapshot. One possible compatible representation is `app_config.config` = signed envelope JSON; `app_config.version` mirrors envelope.version. This requires an atomic, authorized publish path.
3. The Admin APK authenticates through Supabase Auth using an individual admin account; use short-lived access tokens and refresh securely. Never embed the database password, `sb_secret_...`, service-role key or GitHub PAT in an APK.
4. The server checks the authenticated publisher, validates incoming snapshots, prevents lost updates with expected-version compare-and-swap, signs the approved envelope **on the server**, and atomically publishes it. An Edge Function or other trusted backend is required for signing. Do not store the signing private key in the app.
5. Config fetches via HTTPS with the publishable key, validates RSA signature and envelope schema, rejects rollback/invalid versions, then updates its verified cache. Network errors preserve the last known good publication.
6. Keep local ABT/HEX edits, import/export, FAQ, splash, licenses and existing settings untouched. Preserve existing APK package IDs and signing certificate.
7. Test unauthorized writes, malformed envelopes, invalid signatures, conflicting admin updates, offline startup, large splash/FAQ, and two-phone synchronization before changing production.
8. After both APKs pass end-to-end tests, remove GitHub PAT UI and retire GitHub as a configuration backend. GitHub Actions can remain the APK build pipeline.

## Current status
Supabase SQL/RLS setup and public REST GET were verified manually in the dashboard. Android migration and Edge Function publication are **not implemented yet**.
