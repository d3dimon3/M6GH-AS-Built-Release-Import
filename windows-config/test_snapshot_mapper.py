import json
import unittest
from snapshot_mapper import parse_snapshot

class SnapshotMapperTests(unittest.TestCase):
    def test_android_row_field(self):
        snap = {"schema": 2, "kind": "mazda6gh-admin-settings",
                "stores": {"run35_settings": {"features": json.dumps([
                    {"id": "rvm", "module": "IC", "row": "720-01-02",
                     "mode": "HEX", "indices": "0", "on": "8", "off": "0"}])}}}
        features = parse_snapshot(snap)
        self.assertEqual(len(features), 1)
        self.assertEqual(features[0].address, "720-01-02")

    def test_rejects_unsigned_format(self):
        with self.assertRaises(ValueError):
            parse_snapshot({"schema": 1, "kind": "other", "stores": {}})

if __name__ == "__main__":
    unittest.main()
