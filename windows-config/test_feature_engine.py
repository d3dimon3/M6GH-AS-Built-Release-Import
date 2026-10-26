"""Unit tests for Windows feature engine; run: python -m unittest discover -s windows-config -p 'test_*.py'"""
import unittest
from feature_engine import Feature, FeatureEngine, checksum_valid, recalc, changed_positions


class EngineTests(unittest.TestCase):
    def test_checksum_recalculation(self):
        row = recalc("720-01-01", "1F00 6611 803F")
        self.assertTrue(checksum_valid("720-01-01", row))
        self.assertEqual(recalc("720-01-01", row), row)

    def test_hex_toggle_and_original_baseline(self):
        f = Feature("kls", "IC", "720-01-01", "HEX", "1", "F", "B")
        engine = FeatureEngine()
        original = recalc(f.address, "1B00 6611 8000")
        self.assertFalse(engine.state(f, original))
        changed = engine.apply("IC", f.address, original, f, True, [f])
        self.assertTrue(engine.state(f, changed))
        self.assertTrue(checksum_valid(f.address, changed))
        restored = engine.apply("IC", f.address, changed, f, False, [f])
        self.assertEqual(restored, original)

    def test_bits_mode(self):
        f = Feature("bit", "IC", "720-01-01", "BITS", "2", "", byte_index=1)
        engine = FeatureEngine()
        original = recalc(f.address, "0000 6611 8000")
        changed = engine.apply("IC", f.address, original, f, True, [f])
        self.assertTrue(engine.state(f, changed))
        self.assertEqual(changed.replace(" ", "")[2:4], "04")

    def test_seeded_on_requires_off(self):
        f = Feature("kls", "IC", "720-01-01", "HEX", "1", "F")
        engine = FeatureEngine()
        engine.seed(f, True)
        original = recalc(f.address, "1F00 6611 8000")
        with self.assertRaisesRegex(ValueError, "OFF"):
            engine.apply("IC", f.address, original, f, False, [f])

    def test_blue_highlight_positions(self):
        self.assertEqual(changed_positions("1B00", "1F00"), {1})


if __name__ == "__main__":
    unittest.main()
