#!/usr/bin/env python3
"""Trusted one-draft Supabase publisher. Run ONLY in a private server/CI environment.

Required environment:
 SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, CATALOG_SIGNING_PRIVATE_KEY_PEM
Optional: PUBLISHER_DRAFT_ID (UUID). Without it, the oldest pending draft is used.

The database RPC enforces atomic compare-and-swap and draft status transition.
Do not run this in Android or put these credentials in the repository.
"""
import base64
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from uuid import UUID

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from validate_draft import validate_snapshot

MAX_HTTP_BYTES = 1024 * 1024


def api(path, method="GET", body=None):
    base = os.environ["SUPABASE_URL"].rstrip("/")
    if not base.startswith("https://") or not base.endswith(".supabase.co"):
        raise ValueError("Invalid Supabase project URL")
    key = os.environ["SUPABASE_SERVICE_ROLE_KEY"]
    data = None if body is None else json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    request = urllib.request.Request(
        base + path,
        data=data,
        method=method,
        headers={
            "apikey": key,
            "Authorization": "Bearer " + key,
            "Accept": "application/json",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            result = response.read(MAX_HTTP_BYTES + 1)
            if len(result) > MAX_HTTP_BYTES:
                raise ValueError("Response too large")
            return json.loads(result) if result else None
    except urllib.error.HTTPError as error:
        # Log only a short, machine-readable error code; never dump bodies or credentials.
        diagnostic = error.headers.get("x-supabase-error-code", "")
        try:
            error_payload = json.loads(error.read(4096))
            if isinstance(error_payload, dict):
                diagnostic = diagnostic or error_payload.get("code", "")
        except (ValueError, UnicodeError):
            pass
        if not isinstance(diagnostic, str) or not diagnostic.replace("_", "").replace("-", "").isalnum() or len(diagnostic) > 80:
            diagnostic = ""
        suffix = f" (code: {diagnostic})" if diagnostic else ""
        raise RuntimeError(f"Supabase {method} failed: HTTP {error.code}{suffix}") from error


def main():
    for name in ("SUPABASE_URL", "SUPABASE_SERVICE_ROLE_KEY", "CATALOG_SIGNING_PRIVATE_KEY_PEM"):
        if not os.environ.get(name):
            raise RuntimeError(f"Missing required secret: {name}")

    requested_id = os.environ.get("PUBLISHER_DRAFT_ID")
    if requested_id:
        requested_id = str(UUID(requested_id))
        filter_expr = "id=eq." + urllib.parse.quote(requested_id)
    else:
        filter_expr = "status=eq.pending"
    drafts = api("/rest/v1/admin_config_drafts?select=id,base_version,snapshot,status&" +
                 filter_expr + "&order=created_at.asc&limit=1")
    if not drafts:
        print("No draft to publish")
        return
    draft = drafts[0]
    if draft["status"] != "pending":
        raise RuntimeError("Draft is not pending")

    rows = api("/rest/v1/app_config?id=eq.main&select=version&limit=1")
    if not rows or len(rows) != 1:
        raise RuntimeError("Publication row missing")
    expected = int(rows[0]["version"])
    if int(draft["base_version"]) != expected:
        raise RuntimeError("Draft is stale; refusing to overwrite newer publication")

    # Match the Android signature contract exactly: UTF-8 payload and PKCS1v15 SHA256.
    raw = validate_snapshot(draft["snapshot"])
    version = expected + 1
    key_bytes = os.environ["CATALOG_SIGNING_PRIVATE_KEY_PEM"].replace("\\n", "\n").encode("utf-8")
    key = serialization.load_pem_private_key(key_bytes, password=None)
    if not isinstance(key, rsa.RSAPrivateKey) or key.key_size < 3072:
        raise RuntimeError("Catalog signing key must be RSA >= 3072 bits")
    message = f"MAZDA6GH-SETTINGS-V2\n{version}\n{raw}".encode("utf-8")
    signature = base64.b64encode(key.sign(message, padding.PKCS1v15(), hashes.SHA256())).decode("ascii")
    envelope = {
        "schema": 2,
        "kind": "mazda6gh-published-settings",
        "version": version,
        "snapshot_payload": raw,
        "signature": signature,
    }

    result = api("/rest/v1/rpc/publish_admin_draft", method="POST", body={
        "p_draft_id": draft["id"],
        "p_expected_version": expected,
        "p_envelope": envelope,
    })
    if int(result) != version:
        raise RuntimeError("Unexpected publication version")
    print(f"Published draft {draft['id']} as version {version}")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"Publication stopped: {error}", file=sys.stderr)
        sys.exit(1)
