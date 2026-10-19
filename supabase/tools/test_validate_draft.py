"""Offline checks for schema-2 server-side validation; no Supabase credentials needed."""
import copy
import unittest

from validate_draft import InvalidDraft, validate_snapshot


def valid():
    return {
        "schema": 2, "kind": "mazda6gh-admin-settings",
        "stores": {
            "run35_settings": {"features": '[{"id":"1","module":"IC","row":"720-01-01","mode":"hex","indices":"0","on":"8"}]'},
            "studio_admin_rows": {},
            "studio_custom_modules": {"catalog": "[]"},
            "studio_admin_custom_rows": {},
            "studio_faq": {"entries": '[{"name":"FAQ","description":"Text"}]'},
        },
    }


class ValidationTests(unittest.TestCase):
    def test_accept_valid(self):
        self.assertIn("mazda6gh-admin-settings", validate_snapshot(valid()))

    def test_reject_missing_required_store(self):
        d = valid()
        del d["stores"]["studio_admin_rows"]
        with self.assertRaises(InvalidDraft):
            validate_snapshot(d)

    def test_reject_unexpected_store(self):
        d = valid()
        d["stores"]["license_secrets"] = {"key": "private"}
        with self.assertRaises(InvalidDraft):
            validate_snapshot(d)

    def test_reject_malformed_features(self):
        d = valid()
        d["stores"]["run35_settings"]["features"] = "[{}]"
        with self.assertRaises(InvalidDraft):
            validate_snapshot(d)

    def test_reject_bad_faq(self):
        d = valid()
        d["stores"]["studio_faq"]["entries"] = '[{"name":"A","description":"a"},{"name":"a","description":"b"}]'
        with self.assertRaises(InvalidDraft):
            validate_snapshot(d)

    def test_reject_empty(self):
        d = valid()
        d["stores"]["run35_settings"] = {}
        d["stores"]["studio_custom_modules"] = {}
        with self.assertRaises(InvalidDraft):
            validate_snapshot(d)


if __name__ == "__main__":
    unittest.main()
