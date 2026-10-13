import unittest
from datetime import datetime, timezone
from pathlib import Path
import importlib.util


MODULE_PATH = Path(__file__).resolve().parents[1] / "manifest.py"
SPEC = importlib.util.spec_from_file_location("manifest", MODULE_PATH)
manifest = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(manifest)


class ManifestTest(unittest.TestCase):
    def test_integrity_manifest_is_prepared_not_passed(self):
        config = {
            "environment": "local", "mode": "integrity",
            "parameters": {"campaign_id": 1, "total_requests": 100},
            "infrastructure": {"app_instance_count": 1},
        }
        result = manifest.create_manifest(
            config, now=datetime(2026, 10, 13, tzinfo=timezone.utc),
            run_id="fixture-run"
        )
        self.assertEqual(result["status"], "PREPARED")
        self.assertEqual(result["run_id"], "fixture-run")
        self.assertEqual(result["parameters"]["total_requests"], 100)
        self.assertEqual(len(result["git_sha"]), 40)

    def test_capacity_requires_target_or_stages(self):
        config = {
            "environment": "prod", "mode": "capacity",
            "parameters": {"campaign_stock": 1000}, "infrastructure": {},
        }
        with self.assertRaisesRegex(ValueError, "target_rps"):
            manifest.validate_config(config)
        config["parameters"]["rps_stages"] = [100, 200]
        manifest.validate_config(config)

    def test_unknown_fields_are_rejected(self):
        config = {
            "environment": "local", "mode": "integrity",
            "parameters": {"campaign_id": 1, "total_requests": 10},
            "infrastructure": {"db_password": "must-not-be-written"},
        }
        with self.assertRaisesRegex(ValueError, "db_password"):
            manifest.validate_config(config)

    def test_invalid_positive_integer_is_rejected(self):
        config = {
            "environment": "local", "mode": "integrity",
            "parameters": {"campaign_id": 1, "total_requests": True},
            "infrastructure": {},
        }
        with self.assertRaisesRegex(ValueError, "total_requests"):
            manifest.validate_config(config)


if __name__ == "__main__":
    unittest.main()
