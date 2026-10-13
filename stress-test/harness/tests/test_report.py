import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "report.py"
SPEC = importlib.util.spec_from_file_location("report", MODULE_PATH)
report = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(report)


def metric(**values):
    return {"values": values}


class ReportTest(unittest.TestCase):
    def setUp(self):
        self.manifest = {
            "schema_version": 1, "run_id": "test-run", "status": "PREPARED",
            "mode": "integrity", "parameters": {"total_requests": 100},
        }
        self.summary = {"metrics": {
            "iterations": metric(count=100),
            "dropped_iterations": metric(count=0),
            "http_req_failed": metric(rate=0),
            "participation_success": metric(count=100),
            "participation_fail": metric(count=0),
            "http_req_duration": metric(**{"p(95)": 50, "p(99)": 70}),
        }}

    def test_k6_only_never_passes(self):
        result = report.summarize(self.manifest, self.summary)
        self.assertEqual(result["status"], "INCONCLUSIVE")
        self.assertEqual(result["observations"]["accepted_202"], 100)
        self.assertIn("db_new_rows_and_consistency", result["unverified"])

    def test_known_count_mismatch_fails(self):
        self.summary["metrics"]["participation_success"] = metric(count=99)
        result = report.summarize(self.manifest, self.summary)
        self.assertEqual(result["status"], "FAIL")
        self.assertIn("accepted_count_mismatch", result["violations"])

    def test_dropped_iterations_fail(self):
        self.summary["metrics"]["dropped_iterations"] = metric(count=2)
        result = report.summarize(self.manifest, self.summary)
        self.assertEqual(result["status"], "FAIL")

    def test_missing_metrics_remain_inconclusive(self):
        del self.summary["metrics"]["http_req_duration"]
        result = report.summarize(self.manifest, self.summary)
        self.assertEqual(result["status"], "INCONCLUSIVE")
        self.assertIn("api_p95_ms_all_phases", result["missing_k6_metrics"])

    def test_capacity_counts_are_distinct_from_integrity(self):
        self.manifest["mode"] = "capacity"
        self.summary["metrics"].update({
            "participation_accepted_202": metric(count=90),
            "participation_rejected_400": metric(count=5),
            "participation_rate_limited_429": metric(count=5),
            "participation_error": metric(count=0),
            "api_duration_ms": metric(**{"p(95)": 80, "p(99)": 110}),
        })
        result = report.summarize(self.manifest, self.summary)
        self.assertEqual(result["status"], "INCONCLUSIVE")
        self.assertEqual(result["observations"]["rate_limited_429"], 5)

    def test_invalid_k6_values_are_not_reported_as_good(self):
        self.summary["metrics"]["dropped_iterations"] = metric(count=-1)
        result = report.summarize(self.manifest, self.summary)
        self.assertEqual(result["status"], "INCONCLUSIVE")
        self.assertIn("dropped_iterations", result["missing_k6_metrics"])


if __name__ == "__main__":
    unittest.main()
