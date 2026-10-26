"""Read-only verified Supabase publication transport for Windows Config.

The Android publisher signs the exact UTF-8 snapshot_payload, not re-serialized JSON.
Requires: pip install cryptography
No secrets, service-role credentials, admin writes or local state mutations.
"""
from __future__ import annotations

import base64
import json
import re
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any

# Identical RSA public key to Android CatalogSignature.java. Public, not secret.
CATALOG_PUBLIC_KEY_BASE64 = (
    "MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEAoqrQX71QDDkyQ9Jru5IwEjPw6GUclNHYD1Ve3I9Le/7+p7LIOR6h914ZuVBPrPFIptTrRkshmieU/YgYYd/eURvJHn0ovKPOrt2wytN5rv/teah3pERQDFpsZaaL/ZJZ73yH4sijciaGjFCzhjLNXDB3A+kkIvpqIQtVXARobF15JTJ8I7V4TLBFc/s7gSlUHCDsvzhY1yLe6zdH8DoIAZlzZVhVI/wcB1Qm8pSAERkE6f8QPPPYyuxfydFMIBrBwFNjtCajjHA7wpURuZKotwisKVAA3w6iFcnyVhgmQH6D7fD+gUPTj9vaPHzzSIAghQvAA3qL3+06C4uLEtSZ3vodcYxKf18+VL/bUi6558XPrsQ/1NQ+mEbGTFDx2npV6iXwWe21YR6t1DiD2Gl09fJo5pyqaAzSTF5dQ3jFNjvqL0xOXHbIpTWnViy54GM39KHfg2R6rzAC9SxTUr8z9bky6K/ZXMYmoBLPwyf90+RTaqR9PzmFWkGxewXSjHp7AgMBAAE="
)
MAX_RESPONSE_BYTES = 512 * 1024
MAX_PAYLOAD_CHARS = 262144


@dataclass(frozen=True)
class VerifiedPublication:
    version: int
    snapshot: dict[str, Any]


def parse_and_verify(row: dict[str, Any]) -> VerifiedPublication:
    """Reject any unsigned, malformed or mismatched-version publication."""
    from cryptography.exceptions import InvalidSignature
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding

    document = row.get("config")
    if not isinstance(document, dict):
        raise ValueError("Missing publication")
    if type(document.get("schema")) is not int or document["schema"] != 2:
        raise ValueError("Unsupported publication schema")
    if document.get("kind") != "mazda6gh-published-settings":
        raise ValueError("Unsupported publication kind")
    version = document.get("version")
    if type(version) is not int or version < 1 or row.get("version") != version:
        raise ValueError("Publication version mismatch")
    raw = document.get("snapshot_payload")
    if not isinstance(raw, str) or not 0 < len(raw) <= MAX_PAYLOAD_CHARS:
        raise ValueError("Invalid snapshot size")
    signature = document.get("signature")
    if not isinstance(signature, str) or not 100 <= len(signature) <= 2048:
        raise ValueError("Invalid signature")
    snapshot = json.loads(raw)
    if not isinstance(snapshot, dict):
        raise ValueError("Invalid snapshot structure")
    # Signature is verified over the EXACT publisher payload, before any application.
    message = ("MAZDA6GH-SETTINGS-V2" + chr(10) + str(version) + chr(10) + raw).encode("utf-8")
    key = serialization.load_der_public_key(base64.b64decode(CATALOG_PUBLIC_KEY_BASE64, validate=True))
    try:
        key.verify(base64.b64decode(signature, validate=True), message,
                   padding.PKCS1v15(), hashes.SHA256())
    except InvalidSignature as exc:
        raise ValueError("Invalid publisher RSA signature") from exc
    return VerifiedPublication(version, snapshot)


def download_verified_row(project_url: str, publishable_key: str) -> dict:
    if not re.fullmatch(r"https://[a-z0-9-]+\.supabase\.co/?", project_url):
        raise ValueError("Invalid Supabase URL")
    if not publishable_key.startswith("sb_publishable_"):
        raise ValueError("Expected publishable key (never service-role)")
    url = project_url.rstrip("/") + "/rest/v1/app_config?id=eq.main&select=config,version&limit=1"
    req = urllib.request.Request(url, headers={
        "apikey": publishable_key,
        "Accept": "application/json",
        "Cache-Control": "no-cache",
    })
    with urllib.request.urlopen(req, timeout=10) as response:
        if response.status != 200:
            raise ValueError(f"Supabase HTTP {response.status}")
        raw = response.read(MAX_RESPONSE_BYTES + 1)
    if len(raw) > MAX_RESPONSE_BYTES:
        raise ValueError("Supabase response too large")
    rows = json.loads(raw)
    if not isinstance(rows, list) or len(rows) != 1:
        raise ValueError("No published configuration")
    parse_and_verify(rows[0])
    return rows[0]


def download_verified(project_url: str, publishable_key: str) -> VerifiedPublication:
    return parse_and_verify(download_verified_row(project_url, publishable_key))
