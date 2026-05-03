import os

SLACK_WEBHOOK_URL = os.environ["SLACK_WEBHOOK_URL"]
PROMETHEUS_URL    = os.environ.get("PROMETHEUS_URL", "http://prometheus:9090")
AWS_REGION        = os.environ.get("AWS_REGION", "ap-northeast-2")
ASG_NAME          = os.environ.get("ASG_NAME", "batch-kafka-app-asg")
RDS_ID            = os.environ.get("RDS_ID", "batch-kafka-db")
BATCH_API_URL     = os.environ.get(
    "BATCH_API_URL",
    "http://alb-batch-kafka-api-1351817547.ap-northeast-2.elb.amazonaws.com",
)
BATCH_CAMPAIGN_ID = int(os.environ.get("BATCH_CAMPAIGN_ID", "0"))

# P1
HTTP_5XX_THRESHOLD           = 1
REDIS_QUEUE_WARNING          = 1_050_000  # 70%  (MAX_QUEUE_SIZE 1.5M 기준)
REDIS_QUEUE_CRITICAL         = 1_275_000  # 85%  (MAX_QUEUE_SIZE 1.5M 기준)

# P2
CPU_WARNING_PERCENT          = 80.0
CPU_CRITICAL_PERCENT         = 90.0
KAFKA_LAG_WARNING            = 500
KAFKA_LAG_CRITICAL           = 1_000
CONSUMER_LATENCY_WARNING_MS  = 50
CONSUMER_LATENCY_CRITICAL_MS = 200
HIKARI_PENDING_THRESHOLD     = 1

# P3
RDS_CPU_WARNING_PERCENT      = 60.0
BRIDGE_CYCLE_WARNING_SECONDS = 60

# Cooldown
COOLDOWN_SECONDS = 300  # 5분
