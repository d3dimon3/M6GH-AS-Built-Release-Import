"""User-local Supabase connection settings (public publishable key only)."""
import json
import os
from pathlib import Path

def settings_path():
    root = os.environ.get("LOCALAPPDATA", str(Path.home() / "AppData" / "Local"))
    return Path(root) / "M6GH-AS-Built-Config" / "connection.json"

def load_key():
    try:
        data = json.loads(settings_path().read_text(encoding="utf-8"))
        value = data.get("publishable_key", "")
        return value if isinstance(value, str) and value.startswith("sb_publishable_") else ""
    except (OSError, ValueError, TypeError):
        return ""

def save_key(key):
    key = key.strip()
    if not key.startswith("sb_publishable_") or len(key) > 1024:
        raise ValueError("Нужен публичный Supabase publishable key (sb_publishable_)")
    path = settings_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"publishable_key": key}), encoding="utf-8")
