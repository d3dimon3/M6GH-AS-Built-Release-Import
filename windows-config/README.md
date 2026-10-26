# M6GH AS-Built Config — Windows 10/11 (independent prototype)

This directory is an **independent Windows application**, developed in branch `windows-config-v1`.
It does not change the Android Config, Android Admin, or Android Config AS-Built APKs.

## Stage 1 — Windows UI + local ABT viewer

Run `python main.py` with Python 3.10+ on Windows 10/11. The app uses only Python's standard library (Tkinter).
The window is wide, with a gray background, red borders, module tabs and local ABT rows. There is no Mazda banner.
`Open ABT` parses address/value rows; `Save ABT` saves the loaded file's original text, without rewriting checksums or configuration.

**Important:** This is a viewer/prototype, not yet a replacement for Android Config.
Checkboxes are not shown until the Android configuration engine has been ported and tested.
Online Supabase signed catalog verification, license activation, embedded admin and ABT editing are **not implemented yet**. Never trust or publish unsigned remote settings.

## Stage 2 — shared feature logic (source port)\n\n`feature_engine.py` ports the Android HEX/BITS toggles, imported ON state, OFF protection, changed positions and checksum. `test_feature_engine.py` contains initial regression tests. These functions are **not yet connected to the Windows UI**, and the tests have not yet been executed on Windows.\n\n## Planned next steps

1. Port and test ABT parsing and the module-specific feature/HEX/BITS rules against Android fixtures.
2. Port signed Supabase publication verification, using the existing trusted public key.
3. Add the Windows Device ID + RSA-signed activation flow. Windows IDs differ from Android IDs; the existing Android key generator needs a compatible Windows issuance path.
4. Port the embedded admin controls without embedding service-role keys or bypassing server publication authorization.
5. Package as a standalone Windows 10/11 executable and test on a real Windows PC.

The Windows application will read the same **published** configuration as Android, but must keep separate local storage and licensing. No Supabase database schema changes are planned.

## Stage 4 — signed Supabase reader (not wired into GUI yet)

`supabase_publication.py` downloads the single `app_config` published row and verifies the same RSA PKCS#1 v1.5 SHA-256 signature over the exact `MAZDA6GH-SETTINGS-V2` payload used by Android. It requires `cryptography` (`pip install cryptography`) and a public Supabase publishable key supplied at runtime. It intentionally does not contain any secret keys, does not publish drafts, and does not change the Android database. Before applying the snapshot, port `AdminSnapshot.validate` and feature mapping and run parity tests against Android-signed fixtures. This stage has not been executed on a Windows computer yet.
