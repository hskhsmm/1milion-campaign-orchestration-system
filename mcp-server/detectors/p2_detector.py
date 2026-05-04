"""P2 감지 — 앱 CPU / Kafka lag / Consumer 지연 / HikariCP pending."""
import logging
from datetime import datetime, timedelta, timezone

import boto3
import requests

import config
from slack import send_alert
from state import check_and_record, reset_alert

logger = logging.getLogger(__name__)

_cw = boto3.client("cloudwatch", region_name=config.AWS_REGION)


# ---------------------------------------------------------------------------
# 내부 헬퍼
# ---------------------------------------------------------------------------

def _query_prometheus(promql: str) -> float | None:
    try:
        resp = requests.get(
            f"{config.PROMETHEUS_URL}/api/v1/query",
            params={"query": promql},
            timeout=5,
        )
        resp.raise_for_status()
        result = resp.json().get("data", {}).get("result", [])
        if not result:
            return None
        return float(result[0]["value"][1])
    except Exception as e:
        logger.error("Prometheus 쿼리 실패 [%s]: %s", promql, e)
        return None


def _query_cloudwatch_asg_cpu() -> float | None:
    """ASG 전체 인스턴스 평균 CPU (최근 1분)."""
    now = datetime.now(timezone.utc)
    try:
        resp = _cw.get_metric_statistics(
            Namespace="AWS/EC2",
            MetricName="CPUUtilization",
            Dimensions=[{"Name": "AutoScalingGroupName", "Value": config.ASG_NAME}],
            StartTime=now - timedelta(minutes=2),
            EndTime=now,
            Period=60,
            Statistics=["Average"],
        )
        datapoints = resp.get("Datapoints", [])
        if not datapoints:
            return None
        return max(dp["Average"] for dp in datapoints)
    except Exception as e:
        logger.error("CloudWatch CPU 조회 실패: %s", e)
        return None


# ---------------------------------------------------------------------------
# 앱 CPU 감지
# ---------------------------------------------------------------------------

def _check_cpu() -> None:
    cpu = _query_cloudwatch_asg_cpu()
    if cpu is None:
        return

    logger.info("ASG CPU=%.1f%%", cpu)

    if cpu >= config.CPU_CRITICAL_PERCENT:
        key = "cpu_critical"
        if check_and_record(key, config.COOLDOWN_SECONDS):
            send_alert(
                "P2",
                "앱 CPU CRITICAL",
                f"ASG `{config.ASG_NAME}` 평균 CPU *{cpu:.1f}%* "
                f"(임계값 {config.CPU_CRITICAL_PERCENT}%)\n"
                f"ASG Scale-out 또는 인스턴스 타입 확인 필요",
            )
            logger.warning("P2 CPU CRITICAL=%.1f%%", cpu)
        reset_alert("cpu_warning")

    elif cpu >= config.CPU_WARNING_PERCENT:
        key = "cpu_warning"
        if check_and_record(key, config.COOLDOWN_SECONDS):
            send_alert(
                "P2",
                "앱 CPU WARNING",
                f"ASG `{config.ASG_NAME}` 평균 CPU *{cpu:.1f}%* "
                f"(임계값 {config.CPU_WARNING_PERCENT}%)\n"
                f"트래픽 추이 모니터링 권장",
            )
            logger.warning("P2 CPU WARNING=%.1f%%", cpu)

    else:
        reset_alert("cpu_critical")
        reset_alert("cpu_warning")


# ---------------------------------------------------------------------------
# Kafka lag 감지
# ---------------------------------------------------------------------------

def _check_kafka_lag() -> None:
    promql = "sum(kafka_consumergroup_lag_sum{topic='participation-events'})"
    lag = _query_prometheus(promql)
    if lag is None:
        return

    lag = int(lag)
    logger.info("Kafka lag=%d", lag)

    if lag >= config.KAFKA_LAG_CRITICAL:
        key = "kafka_lag_critical"
        if check_and_record(key, config.COOLDOWN_SECONDS):
            send_alert(
                "P2",
                "Kafka lag CRITICAL",
                f"Consumer group lag *{lag:,}* "
                f"(임계값 {config.KAFKA_LAG_CRITICAL:,})\n"
                f"Consumer 처리 속도 저하 또는 재균형 발생 가능",
            )
            logger.warning("P2 Kafka lag CRITICAL=%d", lag)
        reset_alert("kafka_lag_warning")

    elif lag >= config.KAFKA_LAG_WARNING:
        key = "kafka_lag_warning"
        if check_and_record(key, config.COOLDOWN_SECONDS):
            send_alert(
                "P2",
                "Kafka lag WARNING",
                f"Consumer group lag *{lag:,}* "
                f"(임계값 {config.KAFKA_LAG_WARNING:,})\n"
                f"일시적 스파이크인지 추이 확인 권장",
            )
            logger.warning("P2 Kafka lag WARNING=%d", lag)

    else:
        reset_alert("kafka_lag_critical")
        reset_alert("kafka_lag_warning")


# ---------------------------------------------------------------------------
# Consumer 지연 감지
# ---------------------------------------------------------------------------

def _check_consumer_latency() -> None:
    promql = "consumer_pending_to_success_latency_seconds_max * 1000"
    latency = _query_prometheus(promql)
    if latency is None:
        return

    logger.info("Consumer latency=%.1fms", latency)

    if latency >= config.CONSUMER_LATENCY_CRITICAL_MS:
        key = "consumer_latency_critical"
        if check_and_record(key, config.COOLDOWN_SECONDS):
            send_alert(
                "P2",
                "Consumer 지연 CRITICAL",
                f"Consumer 처리 지연 *{latency:.0f}ms* "
                f"(임계값 {config.CONSUMER_LATENCY_CRITICAL_MS}ms)\n"
                f"DB INSERT 병목 또는 Kafka rebalancing 의심",
            )
            logger.warning("P2 consumer latency CRITICAL=%.1fms", latency)
        reset_alert("consumer_latency_warning")

    elif latency >= config.CONSUMER_LATENCY_WARNING_MS:
        key = "consumer_latency_warning"
        if check_and_record(key, config.COOLDOWN_SECONDS):
            send_alert(
                "P2",
                "Consumer 지연 WARNING",
                f"Consumer 처리 지연 *{latency:.0f}ms* "
                f"(임계값 {config.CONSUMER_LATENCY_WARNING_MS}ms)\n"
                f"HikariCP pending 및 RDS CPU 함께 확인 권장",
            )
            logger.warning("P2 consumer latency WARNING=%.1fms", latency)

    else:
        reset_alert("consumer_latency_critical")
        reset_alert("consumer_latency_warning")


# ---------------------------------------------------------------------------
# HikariCP pending 감지
# ---------------------------------------------------------------------------

def _check_hikari_pending() -> None:
    # 전체 인스턴스 중 최대값 기준
    promql = "max(hikaricp_pending_threads)"
    pending = _query_prometheus(promql)
    if pending is None:
        return

    pending = int(pending)
    logger.info("HikariCP pending=%d", pending)

    if pending >= config.HIKARI_PENDING_THRESHOLD:
        key = "hikari_pending"
        if check_and_record(key, config.COOLDOWN_SECONDS):
            send_alert(
                "P2",
                "HikariCP pending 발생",
                f"DB 커넥션 대기 *{pending}개* "
                f"(임계값 {config.HIKARI_PENDING_THRESHOLD})\n"
                f"RDS CPU 및 slow query 확인 필요",
            )
            logger.warning("P2 HikariCP pending=%d", pending)
    else:
        reset_alert("hikari_pending")


# ---------------------------------------------------------------------------
# 30초 폴링 진입점
# ---------------------------------------------------------------------------

def check_p2() -> None:
    _check_cpu()
    _check_kafka_lag()
    _check_consumer_latency()
    _check_hikari_pending()
