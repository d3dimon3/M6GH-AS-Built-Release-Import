"""Convert verified Android AdminSnapshot v2 to desktop features."""
import json
from feature_engine import Feature

def parse_snapshot(snapshot):
    if snapshot.get("schema") != 2 or snapshot.get("kind") != "mazda6gh-admin-settings":
        raise ValueError("Unsupported snapshot")
    stores = snapshot["stores"]
    settings = stores["run35_settings"]
    data = json.loads(settings.get("features", "[]"))
    if not isinstance(data, list) or len(data) > 500:
        raise ValueError("Invalid features")
    features = []
    for item in data:
        for key in ("id", "module", "row", "mode", "indices", "on"):
            if not str(item.get(key, "")).strip():
                raise ValueError("Missing field: " + key)
        features.append(Feature(
            id=str(item["id"]), module=str(item["module"]),
            address=str(item["row"]).upper(), mode=str(item["mode"]).upper(),
            indices=str(item["indices"]), on=str(item["on"]),
            off=str(item.get("off", "")),
            byte_index=int(item.get("byteIndex", 0)),
            label=str(item.get("label") or item["id"])))
    return features
