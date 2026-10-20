import tempfile
import unittest
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from rules import read_rules, validate_rule, write_rules

DEMO = "dev.smallfish.hooklab.demo"


class RulesTests(unittest.TestCase):
    def test_round_trip(self):
        valid = {"class_name": DEMO + ".ImageProcessor",
                 "method_name": "adjustBrightness",
                 "argument_types": ["float", "int"], "enabled": True}
        with tempfile.TemporaryDirectory() as dir:
            file = Path(dir) / "rules.json"
            write_rules(file, DEMO, [valid])
            self.assertEqual(read_rules(file, DEMO), [valid])

    def test_disallow_other_package(self):
        with self.assertRaises(ValueError):
            validate_rule({"class_name": "com.other.C",
                           "method_name": "fetch", "argument_types": []}, DEMO)

    def test_disallow_wildcards(self):
        with self.assertRaises(ValueError):
            validate_rule({"class_name": DEMO + ".**",
                           "method_name": "*", "argument_types": []}, DEMO)

    def test_wrong_package_rejected(self):
        valid = {"class_name": DEMO + ".ImageProcessor",
                 "method_name": "adjustBrightness", "argument_types": []}
        with tempfile.TemporaryDirectory() as dir:
            file = Path(dir) / "rules.json"
            write_rules(file, DEMO, [valid])
            with self.assertRaises(ValueError):
                read_rules(file, "com.different.app")


if __name__ == "__main__":
    unittest.main()
