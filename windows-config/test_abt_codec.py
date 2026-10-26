import unittest
from abt_codec import parse_checked, decode_index

class AbtTests(unittest.TestCase):
    def test_compact(self):
        rows = parse_checked(";Block 1\n720G1G11F006611803F\n", {"720": "IC"})
        self.assertEqual(rows[0].address, "720-01-01")
        self.assertEqual(rows[0].value, "1F00 6611 803F")
    def test_expanded(self):
        rows = parse_checked("720-01-01 1F00 6611 803F", {"720": "IC"})
        self.assertEqual(rows[0].module, "IC")
    def test_bad_row(self):
        with self.assertRaises(ValueError):
            parse_checked("garbage", {"720": "IC"})
    def test_index(self):
        self.assertEqual(decode_index("G1"), 1)

if __name__ == "__main__":
    unittest.main()
