import os
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

os.environ.setdefault("SLACK_WEBHOOK_URL", "https://example.invalid/slack")
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from detectors import p1_detector  # noqa: E402


class ConsistencyDetectorTest(unittest.TestCase):

    @patch.object(p1_detector, "send_alert")
    @patch.object(p1_detector, "check_and_record", return_value=True)
    @patch.object(p1_detector.requests, "get")
    @patch.object(p1_detector.requests, "post")
    def test_runs_current_dry_run_job_and_reports_anomaly_count(
        self,
        post,
        get,
        check_and_record,
        send_alert,
    ):
        post.return_value = response({
            "success": True,
            "data": {"consistencyRecoveryExecutionId": 77},
        })
        get.return_value = response({
            "success": True,
            "data": {
                "execution": {
                    "status": "COMPLETED",
                    "anomalyCount": 2,
                },
                "results": [
                    {
                        "campaignId": 61,
                        "anomalyType": "REDIS_REMAINING_MISMATCH",
                        "severity": "CRITICAL",
                    },
                    {
                        "campaignId": 61,
                        "anomalyType": "MISSING_ACTIVE_STATE",
                        "severity": "WARNING",
                    },
                ],
            },
        })

        with patch.object(p1_detector.config, "BATCH_CAMPAIGN_ID", 61):
            result = p1_detector.check_consistency()

        self.assertEqual("completed", result["status"])
        self.assertEqual(77, result["executionId"])
        self.assertEqual(2, result["anomalyCount"])
        post.assert_called_once_with(
            f"{p1_detector.config.BATCH_API_URL}/api/admin/consistency-recovery",
            json={
                "requestedBy": "mcp-monitor",
                "dryRun": True,
                "autoFix": False,
                "campaignId": 61,
                "maxCampaigns": 1,
            },
            timeout=10,
        )
        get.assert_called_once_with(
            f"{p1_detector.config.BATCH_API_URL}/api/admin/consistency-recovery/executions/77",
            timeout=10,
        )
        check_and_record.assert_called_once_with(
            "consistency_mismatch",
            p1_detector.config.COOLDOWN_SECONDS,
        )
        send_alert.assert_called_once()
        self.assertEqual("P1", send_alert.call_args.args[0])

    @patch.object(p1_detector, "send_alert")
    @patch.object(p1_detector, "check_and_record", return_value=True)
    @patch.object(p1_detector.requests, "post", side_effect=RuntimeError("connection refused"))
    def test_alerts_when_job_start_fails(self, post, check_and_record, send_alert):
        with patch.object(p1_detector.config, "BATCH_CAMPAIGN_ID", 61):
            result = p1_detector.check_consistency()

        self.assertEqual("error", result["status"])
        check_and_record.assert_called_once_with(
            "consistency_check_failed",
            p1_detector.config.COOLDOWN_SECONDS,
        )
        send_alert.assert_called_once()
        self.assertIn("Job 시작 실패", send_alert.call_args.args[2])

    @patch.object(p1_detector, "send_alert")
    @patch.object(p1_detector, "check_and_record", return_value=True)
    @patch.object(p1_detector.requests, "get", side_effect=RuntimeError("read timeout"))
    @patch.object(p1_detector.requests, "post")
    def test_alerts_when_job_poll_fails(self, post, get, check_and_record, send_alert):
        post.return_value = response({
            "success": True,
            "data": {"consistencyRecoveryExecutionId": 77},
        })

        with patch.object(p1_detector.config, "BATCH_CAMPAIGN_ID", 61):
            result = p1_detector.check_consistency()

        self.assertEqual("error", result["status"])
        self.assertEqual(77, result["executionId"])
        check_and_record.assert_called_once_with(
            "consistency_check_failed",
            p1_detector.config.COOLDOWN_SECONDS,
        )
        send_alert.assert_called_once()
        self.assertIn("Job 결과 조회 실패", send_alert.call_args.args[2])

    @patch.object(p1_detector, "send_alert")
    @patch.object(p1_detector, "check_and_record", return_value=True)
    @patch.object(p1_detector.requests, "post")
    def test_alerts_when_job_times_out(self, post, check_and_record, send_alert):
        post.return_value = response({
            "success": True,
            "data": {"consistencyRecoveryExecutionId": 77},
        })

        with patch.object(p1_detector.config, "BATCH_CAMPAIGN_ID", 61), \
                patch.object(p1_detector.config, "CONSISTENCY_POLL_TIMEOUT_SECONDS", 0):
            result = p1_detector.check_consistency()

        self.assertEqual("timeout", result["status"])
        check_and_record.assert_called_once_with(
            "consistency_check_failed",
            p1_detector.config.COOLDOWN_SECONDS,
        )
        send_alert.assert_called_once()
        self.assertIn("시간 초과", send_alert.call_args.args[2])

    @patch.object(p1_detector, "send_alert")
    @patch.object(p1_detector, "check_and_record", return_value=False)
    @patch.object(p1_detector.requests, "post", side_effect=RuntimeError("connection refused"))
    def test_suppresses_repeated_failure_alert_during_cooldown(
        self,
        post,
        check_and_record,
        send_alert,
    ):
        with patch.object(p1_detector.config, "BATCH_CAMPAIGN_ID", 61):
            result = p1_detector.check_consistency()

        self.assertEqual("error", result["status"])
        check_and_record.assert_called_once_with(
            "consistency_check_failed",
            p1_detector.config.COOLDOWN_SECONDS,
        )
        send_alert.assert_not_called()


def response(payload):
    result = Mock()
    result.json.return_value = payload
    result.raise_for_status.return_value = None
    return result


if __name__ == "__main__":
    unittest.main()
