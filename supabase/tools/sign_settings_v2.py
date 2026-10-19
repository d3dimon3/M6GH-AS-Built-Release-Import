#!/usr/bin/env python3
"""Sign a validated Mazda6GH settings snapshot without printing the private key.

Input: snapshot JSON file exported from Admin; VERSION integer >=1.
Secret: CATALOG_SIGNING_PRIVATE_KEY_PEM environment variable (PEM, RSA).
Output: signed schema-2 envelope. Do NOT commit output or key automatically.
This script is a signing primitive, NOT an approval/publishing endpoint.
"""
import argparse
import base64
import json
import os
from pathlib import Path
from validate_draft import validate_snapshot, InvalidDraft

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--snapshot", required=True)
    parser.add_argument("--version", type=int, required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    if args.version < 1:
        parser.error("version must be >=1")
    raw = Path(args.snapshot).read_text(encoding="utf-8")
    if not raw or len(raw.encode("utf-8")) > 262144:
        parser.error("snapshot exceeds app size limit")
    try:
        obj = json.loads(raw)
        validate_snapshot(obj)
    except (ValueError, InvalidDraft) as exc:
        parser.error(f"invalid snapshot: {exc}")
    pem = os.environ.get("CATALOG_SIGNING_PRIVATE_KEY_PEM", "").replace("\\n", "\n").encode()
    if not pem:
        parser.error("missing signing key environment variable")
    key = serialization.load_pem_private_key(pem, password=None)
    if not isinstance(key, rsa.RSAPrivateKey) or key.key_size < 3072:
        parser.error("expected RSA private key >=3072 bits")
    message = f"MAZDA6GH-SETTINGS-V2\n{args.version}\n{raw}".encode("utf-8")
    signature = key.sign(message, padding.PKCS1v15(), hashes.SHA256())
    doc = {
        "schema": 2,
        "kind": "mazda6gh-published-settings",
        "version": args.version,
        "snapshot_payload": raw,
        "signature": base64.b64encode(signature).decode("ascii"),
    }
    output = Path(args.output)
    if output.exists():
        parser.error("refusing to overwrite existing signed output")
    output.write_text(json.dumps(doc, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"Signed envelope prepared: version={args.version}; NOT published.")

if __name__ == "__main__":
    main()
