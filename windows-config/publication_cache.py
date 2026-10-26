"""Offline cache for a previously RSA-verified Supabase publication.

The signed envelope is saved, never trusted without verifying again on load.
No secret keys, activation tokens, or customer ABT data are stored here.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
from supabase_publication import parse_and_verify, VerifiedPublication

MAX_CACHE_BYTES = 512 * 1024


def cache_path() -> Path:
    root = os.environ.get("LOCALAPPDATA")
    if not root:
        root = str(Path.home() / "AppData" / "Local")
    return Path(root) / "M6GH-AS-Built-Config" / "published-cache.json"


def load_verified_cache() -> VerifiedPublication | None:
    path = cache_path()
    if not path.exists():
        return None
    if path.stat().st_size > MAX_CACHE_BYTES:
        raise ValueError("Cache exceeds size limit")
    row = json.loads(path.read_text(encoding="utf-8"))
    return parse_and_verify(row)


def save_verified_cache(row: dict) -> VerifiedPublication:
    publication = parse_and_verify(row)
    path = cache_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(row, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if len(payload) > MAX_CACHE_BYTES:
        raise ValueError("Cache exceeds size limit")
    temporary = path.with_suffix(".tmp")
    try:
        temporary.write_bytes(payload)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)
    return publication
