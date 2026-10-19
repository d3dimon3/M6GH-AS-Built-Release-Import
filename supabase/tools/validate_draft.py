"""Server-side validation for Mazda6GH Admin schema-2 drafts.

Pure validation only: does not approve, sign, or publish.
Never accept the Android client's own validation as sufficient.
"""
import json

MAX_SNAPSHOT_BYTES = 262144
STORES = ("run35_settings", "studio_admin_rows", "studio_custom_modules",
          "studio_admin_custom_rows", "studio_faq")

class InvalidDraft(ValueError):
    pass

def _fail(message):
    raise InvalidDraft(message)

def _array(value, label):
    if not isinstance(value, str):
        _fail(f"{label} must be a JSON string")
    try:
        parsed = json.loads(value)
    except (ValueError, TypeError) as exc:
        raise InvalidDraft(f"{label} invalid JSON") from exc
    if not isinstance(parsed, list):
        _fail(f"{label} must be an array")
    return parsed

def validate_snapshot(snapshot):
    if not isinstance(snapshot, dict):
        _fail("snapshot must be an object")
    raw = json.dumps(snapshot, ensure_ascii=False, separators=(",", ":"))
    if len(raw.encode("utf-8")) > MAX_SNAPSHOT_BYTES:
        _fail("snapshot too large")
    if snapshot.get("schema") != 2 or snapshot.get("kind") != "mazda6gh-admin-settings":
        _fail("unsupported snapshot")
    stores = snapshot.get("stores")
    if not isinstance(stores, dict):
        _fail("missing stores")
    if set(stores) - set(STORES):
        _fail("unexpected stores")
    for name in STORES[:3]:
        if not isinstance(stores.get(name), dict):
            _fail(f"missing store {name}")
    for name in STORES:
        values = stores.get(name, {})
        if not isinstance(values, dict) or len(values) > 512:
            _fail(f"invalid store {name}")
        for key, value in values.items():
            if not isinstance(key, str) or len(key) > 100:
                _fail("invalid setting key")
            if type(value) not in (str, bool, int, float):
                _fail("invalid setting type")
            if isinstance(value, str) and len(value) > MAX_SNAPSHOT_BYTES:
                _fail("setting too large")
    settings = stores["run35_settings"]
    rows = stores["studio_admin_rows"]
    custom = stores["studio_custom_modules"]
    custom_rows = stores.get("studio_admin_custom_rows", {})
    faq = stores.get("studio_faq", {})
    if not (settings or rows or custom or custom_rows):
        _fail("empty configuration")
    if any(k != "features" and not k.startswith(("module_name_", "module_version_", "appearance_")) for k in settings):
        _fail("invalid run35 key")
    if any(k != "catalog" for k in custom):
        _fail("invalid custom module key")
    if any(k != "entries" for k in faq):
        _fail("invalid FAQ key")
    features = _array(settings.get("features", "[]"), "features")
    modules = _array(custom.get("catalog", "[]"), "catalog")
    if len(features) > 500 or len(modules) > 100:
        _fail("too many features/modules")
    for feature in features:
        if not isinstance(feature, dict) or any(
            not isinstance(feature.get(k), str) or not feature[k].strip()
            for k in ("id", "module", "row", "mode", "indices", "on")
        ):
            _fail("invalid feature")
    for module in modules:
        if not isinstance(module, dict) or any(
            not isinstance(module.get(k), str) or not module[k].strip()
            for k in ("id", "name")
        ):
            _fail("invalid module")
    for name, values in (("studio_admin_rows", rows), ("studio_admin_custom_rows", custom_rows)):
        for key, value in values.items():
            _array(value, f"{name}.{key}")
    entries = _array(faq.get("entries", "[]"), "FAQ")
    if len(entries) > 200:
        _fail("too many FAQ entries")
    names = set()
    for entry in entries:
        if not isinstance(entry, dict) or not {"name", "description"} <= set(entry) or set(entry) - {"name", "description", "extra_text"}:
            _fail("invalid FAQ entry")
        if any(not isinstance(entry.get(k), str) for k in entry):
            _fail("invalid FAQ text")
        name = entry["name"].strip()
        if not name or len(name) > 120 or len(entry["description"]) > 12000 or len(entry.get("extra_text", "")) > 12000:
            _fail("invalid FAQ length")
        if name.casefold() in names:
            _fail("duplicate FAQ name")
        names.add(name.casefold())
    return raw
