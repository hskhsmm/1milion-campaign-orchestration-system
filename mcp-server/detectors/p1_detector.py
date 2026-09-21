"""P1 감지 — 5xx 에러 / Redis Queue 적재량 / 데이터 정합성."""
import logging
import time

import requests

import config
from slack import send_alert
from state import check_and_record, reset_alert

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# 내부 헬퍼
# ---------------------------------------------------------------------------

def _query_prometheus(promql: str) -> float | None:
    """Prometheus instant query. 결과 없으면 None 반환."""
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


# ---------------------------------------------------------------------------
# 5xx 에러 감지
# ---------------------------------------------------------------------------

def _check_5xx() -> None:
    # increase()가 이미 "최근 30초 증가량"을 반환 — 별도 delta 계산 불필요
    promql = 'sum(increase(http_server_requests_seconds_count{status=~"5.."}[30s]))'
    delta = _query_prometheus(promql)
    if delta is None:
        return

    if delta > config.HTTP_5XX_THRESHOLD:
        key = "5xx_error"
        if check_and_record(key, config.COOLDOWN_SECONDS):
            send_alert(
                "P1",
                "5xx 에러 발생",
                f"최근 30초간 5xx 에러 *{delta:.0f}건* 감지\n"
                f"Prometheus: `{promql}`",
            )
            logger.warning("P1 5xx=%.0f", delta)
    else:
        reset_alert("5xx_error")


# ---------------------------------------------------------------------------
# Redis Queue 적재량 감지
# ---------------------------------------------------------------------------

def _check_redis_queue() -> None:
    promql = "max by (campaignId) (redis_queue_size)"
    value = _query_prometheus(promql)
    if value is None:
        return

    queue_size = int(value)

    if queue_size >= config.REDIS_QUEUE_CRITICAL:
        key = "redis_queue_critical"
        if check_and_record(key, config.COOLDOWN_SECONDS):
            send_alert(
                "P1",
                "Redis Queue CRITICAL",
                f"Queue 적재량 *{queue_size:,}* (임계값 {config.REDIS_QUEUE_CRITICAL:,} / 85%)\n"
                f"데이터 유실 위험 — MAX_QUEUE_SIZE 초과 임박",
            )
            logger.warning("P1 redis_queue CRITICAL size=%d", queue_size)
        reset_alert("redis_queue_warning")

    elif queue_size >= config.REDIS_QUEUE_WARNING:
        key = "redis_queue_warning"
        if check_and_record(key, config.COOLDOWN_SECONDS):
            send_alert(
                "P1",
                "Redis Queue WARNING",
                f"Queue 적재량 *{queue_size:,}* (임계값 {config.REDIS_QUEUE_WARNING:,} / 70%)\n"
                f"Consumer 처리 속도 확인 권장",
            )
            logger.warning("P1 redis_queue WARNING size=%d", queue_size)

    else:
        reset_alert("redis_queue_critical")
        reset_alert("redis_queue_warning")


# ---------------------------------------------------------------------------
# 데이터 정합성 검사 (1시간 폴링)
# ---------------------------------------------------------------------------

def check_consistency() -> dict:
    """현재 Spring Batch 정합성 Job을 dry-run으로 실행하고 결과를 알린다."""
    if config.BATCH_CAMPAIGN_ID == 0:
        logger.info("BATCH_CAMPAIGN_ID 미설정 — 정합성 검사 스킵")
        return {"status": "skipped", "reason": "BATCH_CAMPAIGN_ID 미설정"}

    base_url = f"{config.BATCH_API_URL}/api/admin/consistency-recovery"
    try:
        resp = requests.post(
            base_url,
            json={
                "requestedBy": "mcp-monitor",
                "dryRun": True,
                "autoFix": False,
                "campaignId": config.BATCH_CAMPAIGN_ID,
                "maxCampaigns": 1,
            },
            timeout=10,
        )
        resp.raise_for_status()
        payload = resp.json()
        data = payload.get("data") or {}
        execution_id = data["consistencyRecoveryExecutionId"]
    except Exception as e:
        logger.error("정합성 복구 Job 시작 실패: %s", e)
        return {"status": "error", "reason": str(e)}

    deadline = time.monotonic() + config.CONSISTENCY_POLL_TIMEOUT_SECONDS
    while time.monotonic() < deadline:
        try:
            resp = requests.get(f"{base_url}/executions/{execution_id}", timeout=10)
            resp.raise_for_status()
            data = (resp.json().get("data") or {})
            execution = data.get("execution") or {}
            status = execution.get("status", "UNKNOWN")
        except Exception as e:
            logger.error("정합성 복구 Job 결과 조회 실패. executionId=%s error=%s", execution_id, e)
            return {"status": "error", "executionId": execution_id, "reason": str(e)}

        if status == "FAILED":
            if check_and_record("consistency_check_failed", config.COOLDOWN_SECONDS):
                send_alert(
                    "P1",
                    "정합성 검사 실패",
                    f"executionId: *{execution_id}*\nSpring Batch 상태: *FAILED*",
                )
            return {"status": "failed", "executionId": execution_id}

        if status == "COMPLETED":
            anomaly_count = int(execution.get("anomalyCount", 0))
            results = data.get("results") or []
            reset_alert("consistency_check_failed")
            logger.info(
                "정합성 검사 완료 — executionId=%s campaignId=%s anomalies=%d",
                execution_id,
                config.BATCH_CAMPAIGN_ID,
                anomaly_count,
            )

            if anomaly_count > 0:
                if check_and_record("consistency_mismatch", config.COOLDOWN_SECONDS):
                    details = []
                    for result in results[:5]:
                        details.append(
                            f"campaign={result.get('campaignId')} "
                            f"type={result.get('anomalyType')} "
                            f"severity={result.get('severity')}"
                        )
                    detail_text = "\n".join(details) or "상세 결과 없음"
                    send_alert(
                        "P1",
                        "데이터 정합성 이상 탐지",
                        f"campaignId: *{config.BATCH_CAMPAIGN_ID}*\n"
                        f"이상 분류: *{anomaly_count:,}건*\n{detail_text}",
                    )
            else:
                reset_alert("consistency_mismatch")
                send_alert(
                    "OK",
                    "정합성 검사 통과",
                    f"campaignId *{config.BATCH_CAMPAIGN_ID}*, anomaly 0건",
                )

            return {
                "status": "completed",
                "executionId": execution_id,
                "anomalyCount": anomaly_count,
                "resultCount": len(results),
            }

        time.sleep(config.CONSISTENCY_POLL_INTERVAL_SECONDS)

    logger.error("정합성 복구 Job 대기 시간 초과. executionId=%s", execution_id)
    return {"status": "timeout", "executionId": execution_id}


# ---------------------------------------------------------------------------
# 30초 폴링 진입점
# ---------------------------------------------------------------------------

def check_p1() -> None:
    _check_5xx()
    _check_redis_queue()
