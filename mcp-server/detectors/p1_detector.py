"""P1 감지 — 5xx 에러 / Redis Queue 적재량 / 데이터 정합성."""
import logging

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
    promql = "campaign_redis_queue_size"
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
                "P2",
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

def check_consistency() -> None:
    """Redis 재고 카운터 vs DB INSERT 건수 정합성 확인."""
    if config.BATCH_CAMPAIGN_ID == 0:
        logger.info("BATCH_CAMPAIGN_ID 미설정 — 정합성 검사 스킵")
        return

    url = f"{config.BATCH_API_URL}/api/admin/campaigns/{config.BATCH_CAMPAIGN_ID}/consistency"
    try:
        resp = requests.get(url, timeout=10)
        resp.raise_for_status()
        data = resp.json()
    except Exception as e:
        logger.error("정합성 API 호출 실패: %s", e)
        return

    redis_count = data.get("redisCount", 0)
    db_count    = data.get("dbCount", 0)
    diff        = abs(redis_count - db_count)

    logger.info("정합성 검사 — redis=%d db=%d diff=%d", redis_count, db_count, diff)

    if diff > 0:
        key = "consistency_mismatch"
        if check_and_record(key, config.COOLDOWN_SECONDS):
            send_alert(
                "P1",
                "데이터 정합성 불일치",
                f"Redis 확정 건수: *{redis_count:,}*\n"
                f"DB INSERT 건수: *{db_count:,}*\n"
                f"차이: *{diff:,}건* — 유실 또는 중복 가능성",
            )
    else:
        reset_alert("consistency_mismatch")
        send_alert(
            "OK",
            "정합성 검사 통과",
            f"Redis = DB = *{db_count:,}건* 일치",
        )


# ---------------------------------------------------------------------------
# 30초 폴링 진입점
# ---------------------------------------------------------------------------

def check_p1() -> None:
    _check_5xx()
    _check_redis_queue()
