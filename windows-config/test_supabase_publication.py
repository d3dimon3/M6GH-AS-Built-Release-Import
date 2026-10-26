import base64
import json
import unittest
from unittest.mock import patch

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

import supabase_publication as publication


class SignedPayloadTests(unittest.TestCase):
    def test_verification_preserves_literal_backslash_n_in_snapshot(self):
        key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
        public_der = key.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        raw = json.dumps({"text": "Line one\\nLine two", "stores": {}}, ensure_ascii=False)
        version = 7
        message = ("MAZDA6GH-SETTINGS-V2\n" + str(version) + "\n" + raw).encode("utf-8")
        signature = base64.b64encode(key.sign(message, padding.PKCS1v15(), hashes.SHA256())).decode()
        row = {"version": version, "config": {
            "schema": 2, "kind": "mazda6gh-published-settings",
            "version": version, "snapshot_payload": raw, "signature": signature}}
        with patch.object(publication, "CATALOG_PUBLIC_KEY_BASE64", base64.b64encode(public_der).decode()):
            result = publication.parse_and_verify(row)
            self.assertEqual(result.version, version)
            self.assertEqual(result.snapshot["text"], "Line one\\nLine two")
            row["config"]["snapshot_payload"] = raw.replace("\\\\n", "\\n")
            with self.assertRaises(ValueError):
                publication.parse_and_verify(row)


if __name__ == "__main__":
    unittest.main()
