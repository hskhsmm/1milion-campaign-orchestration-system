"""AWS 호출 없이 정상·불완전 정합성 증거를 확인한다."""

import json
import sys
import tempfile
import unittest
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import verdict


def prom_value(value):
    return {"data": {"result": [{"value": [1, str(value)]}]}}


class IntegrityVerdictTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.evidence = Path(temp.name)
        start = datetime(2026, 11, 15, 18, 24, tzinfo=verdict.KST).timestamp()
        self.save("run.json", {
            "mode": "integrity", "purpose": "final", "total_requests": 2,
            "load_start_epoch": start, "load_end_epoch": start + 10,
        })
        hashes = {name: "fixture" for name in verdict.K6_FILES}
        self.save("precheck.json", {
            "expected_md5": hashes, "loadgen_md5": hashes,
            "ssm_image": "app:test", "app_images": {"app1": "app:test"},
            "kafka_lag": 0, "hikari_pending": 0, "asg": {"desired": 1}, "alb_healthy": 1,
        })
        self.save("k6-summary.json", {"metrics": {
            "participation_success": {"count": 2},
            "participation_fail": {"count": 0},
            "participation_5xx": {"count": 0},
            "http_req_failed": {"passes": 0},
            "http_req_duration": {"p(95)": 1121, "p(99)": 1385, "thresholds": {"p(95)<1000": True}},
        }})
        self.redis_log(" inflight=0")
        self.save("drain.json", {"drained": True, "waited_seconds": 2})
        self.save("kafka-lag-final.json", prom_value(0))
        self.save("bridge-published-final.json", prom_value(2))
        self.save("status.json", {"data": {"successCount": 2, "currentStock": 0}})
        (self.evidence / "db-counts.txt").write_text(
            "| total_rows | success_count | dup_sequence_groups | dup_user_groups |\n"
            "+------------+---------------+---------------------+-----------------+\n"
            "| 2          | 2             | 0                   | 0               |\n"
        )
        self.save("consistency.json", {"data": {"execution": {"status": "COMPLETED"}}})
        self.save("scaling.json", [])

    def save(self, name, data):
        (self.evidence / name).write_text(json.dumps(data))

    def redis_log(self, inflight):
        (self.evidence / "redis-watch.log").write_text(
            "2026-11-15 18:23:59 inSet=1 flag=1 llen=0 inflight=0 stock=2 set=[1]\n"
            f"2026-11-15 18:24:12 inSet=0 flag=0 llen=0{inflight} stock=0 set=[]\n"
        )

    def test_p95_failure_is_reported_separately_from_integrity(self):
        result = verdict.evaluate(self.evidence)
        self.assertEqual("PASS", result["verdict"])
        latency = next(c for c in result["checks"] if c["id"] == "client_latency")
        self.assertIn("1초 기준 FAIL", latency["detail"])

    def test_remaining_inflight_fails_even_if_queue_and_lag_are_zero(self):
        self.redis_log(" inflight=3")
        self.assertEqual("FAIL", verdict.evaluate(self.evidence)["verdict"])

    def test_legacy_log_without_final_inflight_is_inconclusive(self):
        self.redis_log("")
        self.assertEqual("INCONCLUSIVE", verdict.evaluate(self.evidence)["verdict"])

    def test_duplicate_publish_is_not_hidden_by_unique_db_rows(self):
        self.save("bridge-published-final.json", prom_value(3))
        self.assertEqual("FAIL", verdict.evaluate(self.evidence)["verdict"])

    def test_missing_publish_evidence_is_inconclusive(self):
        (self.evidence / "bridge-published-final.json").unlink()
        self.assertEqual("INCONCLUSIVE", verdict.evaluate(self.evidence)["verdict"])
