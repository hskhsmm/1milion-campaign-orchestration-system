import os
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

os.environ.setdefault("SLACK_WEBHOOK_URL", "https://example.invalid/slack")
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import alb  # noqa: E402


class AlbLookupTest(unittest.TestCase):
    @patch.object(alb.boto3, "client")
    def test_resolves_current_active_alb(self, client):
        api = Mock()
        api.describe_load_balancers.return_value = {
            "LoadBalancers": [{
                "State": {"Code": "active"},
                "DNSName": "alb-batch-kafka-api-123.ap-northeast-2.elb.amazonaws.com",
            }]
        }
        client.return_value = api

        self.assertEqual(
            "http://alb-batch-kafka-api-123.ap-northeast-2.elb.amazonaws.com",
            alb.current_batch_api_url(),
        )
        client.assert_called_once_with("elbv2", region_name=alb.config.AWS_REGION)
        api.describe_load_balancers.assert_called_once_with(Names=[alb.config.ALB_NAME])

    @patch.object(alb.boto3, "client")
    def test_rejects_inactive_alb(self, client):
        client.return_value.describe_load_balancers.return_value = {
            "LoadBalancers": [{
                "State": {"Code": "provisioning"},
                "DNSName": "alb-batch-kafka-api-123.ap-northeast-2.elb.amazonaws.com",
            }]
        }

        with self.assertRaisesRegex(RuntimeError, "active"):
            alb.current_batch_api_url()

    @patch.object(alb.boto3, "client")
    def test_rejects_unexpected_dns(self, client):
        client.return_value.describe_load_balancers.return_value = {
            "LoadBalancers": [{"State": {"Code": "active"}, "DNSName": "example.invalid"}]
        }

        with self.assertRaisesRegex(RuntimeError, "DNS"):
            alb.current_batch_api_url()


if __name__ == "__main__":
    unittest.main()
