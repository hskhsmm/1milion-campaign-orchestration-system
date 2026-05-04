"""P3 감지 — RDS CPU / Bridge 드레인 사이클."""
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


def _query_cloudwatch_rds_cpu() -> float | None:
    """RDS 인스턴스 CPU 평균 (최근 1분)."""
    now = datetime.now(timezone.utc)
    try:
        resp = _cw.get_metric_statistics(
            Namespace="AWS/RDS",
            MetricName="CPUUtilization",
            Dimensions=[{"Name": "DBInstanceIdentifier", "Value": config.RDS_ID}],
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
        logger.error("CloudWatch RDS CPU 조회 실패: %s", e)
        return None


# ---------------------------------------------------------------------------
# RDS CPU 감지
# ---------------------------------------------------------------------------

def _check_rds_cpu() -> None:
    cpu = _query_cloudwatch_rds_cpu()
    if cpu is None:
        return

    logger.info("RDS CPU=%.1f%%", cpu)

    if cpu >= config.RDS_CPU_WARNING_PERCENT:
        key = "rds_cpu_warning"
        if check_and_record(key, config.COOLDOWN_SECONDS):
            send_alert(
                "P3",
                "RDS CPU WARNING",
                f"DB `{config.RDS_ID}` CPU *{cpu:.1f}%* "
                f"(임계값 {config.RDS_CPU_WARNING_PERCENT}%)\n"
                f"slow query 또는 HikariCP pool 크기 확인 권장\n"
                f"11차 테스트 최대치: 47% — 현재 {cpu:.0f}%는 비정상 수준",
            )
            logger.warning("P3 RDS CPU WARNING=%.1f%%", cpu)
    else:
        reset_alert("rds_cpu_warning")


# ---------------------------------------------------------------------------
# Bridge 드레인 사이클 감지
# ---------------------------------------------------------------------------

def _check_bridge_cycle() -> None:
    """Bridge 사이클 시간이 임계값 초과 시 Queue 드레인 지연 의심."""
    promql = "bridge_drain_duration_seconds_max"
    cycle_sec = _query_prometheus(promql)
    if cycle_sec is None:
        return

    logger.info("Bridge cycle=%.2fs", cycle_sec)

    if cycle_sec >= config.BRIDGE_CYCLE_WARNING_SECONDS:
        key = "bridge_cycle_warning"
        if check_and_record(key, config.COOLDOWN_SECONDS):
            send_alert(
                "P3",
                "Bridge 드레인 사이클 지연",
                f"ParticipationBridge 사이클 *{cycle_sec:.1f}초* "
                f"(임계값 {config.BRIDGE_CYCLE_WARNING_SECONDS}초)\n"
                f"Queue → Kafka 전송 지연 — Redis Queue 적재량 함께 확인\n"
                f"정상 범위: 100ms 간격 스케줄, 사이클당 최대 2,000건 배치",
            )
            logger.warning("P3 Bridge cycle WARNING=%.2fs", cycle_sec)
    else:
        reset_alert("bridge_cycle_warning")


# ---------------------------------------------------------------------------
# 30초 폴링 진입점
# ---------------------------------------------------------------------------

def check_p3() -> None:
    _check_rds_cpu()
    _check_bridge_cycle()
