"""MCP 도구 노출 — Claude가 직접 호출할 수 있는 읽기 전용 운영 도구."""
import logging
import time

import requests
from mcp.server import Server
from mcp.server.sse import SseServerTransport
from mcp.types import TextContent, Tool
from starlette.responses import Response
from starlette.routing import Mount, Route

import config
import state
from detectors.p1_detector import check_consistency, check_p1
from detectors.p2_detector import check_p2
from detectors.p3_detector import check_p3

logger = logging.getLogger(__name__)

mcp_server = Server("mcp-monitor")


# ---------------------------------------------------------------------------
# 도구 목록 정의
# ---------------------------------------------------------------------------

@mcp_server.list_tools()
async def list_tools() -> list[Tool]:
    return [
        Tool(
            name="get_monitor_status",
            description=(
                "현재 alert cooldown 상태를 반환합니다. "
                "어떤 알림이 활성화(쿨다운 중)인지, 마지막 알림 시각이 언제인지 확인합니다."
            ),
            inputSchema={"type": "object", "properties": {}, "required": []},
        ),
        Tool(
            name="run_check",
            description=(
                "P1/P2/P3 detector를 수동으로 즉시 실행합니다. "
                "쿨다운 무시 없이 실제 감지 로직을 실행하며, Slack 알림도 발송됩니다."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "level": {
                        "type": "string",
                        "enum": ["p1", "p2", "p3", "all"],
                        "description": "실행할 detector (p1/p2/p3/all)",
                    }
                },
                "required": ["level"],
            },
        ),
        Tool(
            name="query_prometheus",
            description=(
                "Prometheus에 PromQL 쿼리를 실행하고 결과를 반환합니다. "
                "현재 메트릭 값을 직접 확인할 때 사용합니다."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "promql": {
                        "type": "string",
                        "description": "실행할 PromQL 쿼리 (예: campaign_redis_queue_size)",
                    }
                },
                "required": ["promql"],
            },
        ),
        Tool(
            name="reset_cooldown",
            description=(
                "특정 alert의 쿨다운을 리셋합니다. "
                "쿨다운 중인 알림을 즉시 재발송 가능하게 만들 때 사용합니다. "
                "key를 'all'로 지정하면 모든 쿨다운을 초기화합니다."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "key": {
                        "type": "string",
                        "description": (
                            "리셋할 alert key "
                            "(예: 5xx_error / redis_queue_critical / cpu_warning / all)"
                        ),
                    }
                },
                "required": ["key"],
            },
        ),
        Tool(
            name="trigger_consistency_check",
            description=(
                "Redis 재고 카운터와 DB INSERT 건수 정합성 검사를 즉시 실행합니다. "
                "1시간 주기를 기다리지 않고 수동으로 트리거합니다."
            ),
            inputSchema={"type": "object", "properties": {}, "required": []},
        ),
        Tool(
            name="query_prometheus_range",
            description=(
                "Prometheus에 특정 시간 구간의 메트릭을 조회합니다. "
                "부하 테스트 종료 후 '아까 어디서 문제였나' 분석에 사용합니다. "
                "start/end는 Unix timestamp 또는 'now-1h' 같은 상대 표현 사용 가능."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "promql": {
                        "type": "string",
                        "description": "실행할 PromQL 쿼리",
                    },
                    "start_minutes_ago": {
                        "type": "integer",
                        "description": "조회 시작점 (현재로부터 몇 분 전). 예: 60 → 1시간 전부터",
                    },
                    "end_minutes_ago": {
                        "type": "integer",
                        "description": "조회 종료점 (현재로부터 몇 분 전). 예: 0 → 지금까지",
                        "default": 0,
                    },
                    "step_seconds": {
                        "type": "integer",
                        "description": "데이터 포인트 간격(초). 기본 30초",
                        "default": 30,
                    },
                },
                "required": ["promql", "start_minutes_ago"],
            },
        ),
        Tool(
            name="get_test_report",
            description=(
                "부하 테스트 구간 전체 핵심 메트릭을 한번에 조회해서 요약합니다. "
                "테스트 종료 후 '어디서 문제가 있었나' 파악할 때 사용합니다. "
                "각 메트릭의 최대값과 임계값 초과 여부를 함께 반환합니다."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "start_minutes_ago": {
                        "type": "integer",
                        "description": "테스트 시작 시각 (현재로부터 몇 분 전). 예: 60",
                    },
                    "end_minutes_ago": {
                        "type": "integer",
                        "description": "테스트 종료 시각 (현재로부터 몇 분 전). 예: 0 → 지금",
                        "default": 0,
                    },
                },
                "required": ["start_minutes_ago"],
            },
        ),
    ]


# ---------------------------------------------------------------------------
# 도구 실행 핸들러
# ---------------------------------------------------------------------------

@mcp_server.call_tool()
async def call_tool(name: str, arguments: dict) -> list[TextContent]:
    if name == "get_monitor_status":
        return _handle_get_monitor_status()

    if name == "run_check":
        return _handle_run_check(arguments.get("level", "all"))

    if name == "query_prometheus":
        return _handle_query_prometheus(arguments.get("promql", ""))

    if name == "reset_cooldown":
        return _handle_reset_cooldown(arguments.get("key", ""))

    if name == "trigger_consistency_check":
        return _handle_trigger_consistency_check()

    if name == "query_prometheus_range":
        return _handle_query_prometheus_range(
            promql=arguments.get("promql", ""),
            start_minutes_ago=arguments.get("start_minutes_ago", 60),
            end_minutes_ago=arguments.get("end_minutes_ago", 0),
            step_seconds=arguments.get("step_seconds", 30),
        )

    if name == "get_test_report":
        return _handle_get_test_report(
            start_minutes_ago=arguments.get("start_minutes_ago", 60),
            end_minutes_ago=arguments.get("end_minutes_ago", 0),
        )

    return [TextContent(type="text", text=f"알 수 없는 도구: {name}")]


# ---------------------------------------------------------------------------
# 핸들러 구현
# ---------------------------------------------------------------------------

def _handle_get_monitor_status() -> list[TextContent]:
    now = time.time()
    alert_state = state.list_alerts()

    if not alert_state:
        return [TextContent(type="text", text="현재 활성화된 alert 없음 (모든 쿨다운 초기화 상태)")]

    lines = ["**현재 alert cooldown 상태**\n"]
    for key, last_time in sorted(alert_state.items()):
        elapsed = now - last_time
        remaining = max(0.0, config.COOLDOWN_SECONDS - elapsed)
        lines.append(
            f"- `{key}`: 마지막 알림 {elapsed:.0f}초 전 / "
            f"쿨다운 잔여 {remaining:.0f}초"
        )

    return [TextContent(type="text", text="\n".join(lines))]


def _handle_run_check(level: str) -> list[TextContent]:
    results = []
    checks = {
        "p1": ("P1 (5xx / Redis Queue)", check_p1),
        "p2": ("P2 (CPU / Kafka lag / Consumer / HikariCP)", check_p2),
        "p3": ("P3 (RDS CPU / Bridge 사이클)", check_p3),
    }

    targets = checks.items() if level == "all" else [(level, checks[level])] if level in checks else []

    if not targets:
        return [TextContent(type="text", text=f"알 수 없는 level: {level}")]

    for key, (label, fn) in targets:
        try:
            fn()
            results.append(f"✓ {label} 실행 완료")
        except Exception as e:
            results.append(f"✗ {label} 실행 실패: {e}")

    return [TextContent(type="text", text="\n".join(results))]


def _handle_query_prometheus(promql: str) -> list[TextContent]:
    if not promql:
        return [TextContent(type="text", text="promql 파라미터가 필요합니다.")]

    try:
        resp = requests.get(
            f"{config.PROMETHEUS_URL}/api/v1/query",
            params={"query": promql},
            timeout=5,
        )
        resp.raise_for_status()
        data = resp.json()
        result = data.get("data", {}).get("result", [])

        if not result:
            return [TextContent(type="text", text=f"`{promql}` → 결과 없음 (메트릭 미수집 또는 쿼리 오류)")]

        lines = [f"**Prometheus 쿼리**: `{promql}`\n"]
        for item in result:
            metric = item.get("metric", {})
            value = item["value"][1]
            label_str = ", ".join(f'{k}="{v}"' for k, v in metric.items()) or "(no labels)"
            lines.append(f"- {label_str}: **{value}**")

        return [TextContent(type="text", text="\n".join(lines))]

    except Exception as e:
        return [TextContent(type="text", text=f"Prometheus 쿼리 실패: {e}")]


def _handle_reset_cooldown(key: str) -> list[TextContent]:
    if not key:
        return [TextContent(type="text", text="key 파라미터가 필요합니다.")]

    if key == "all":
        cleared = state.reset_all()
        return [TextContent(type="text", text=f"전체 쿨다운 초기화 완료: {cleared}")]

    if key in state.list_alerts():
        state.reset_alert(key)
        return [TextContent(type="text", text=f"`{key}` 쿨다운 리셋 완료")]

    return [TextContent(type="text", text=f"`{key}` 는 현재 쿨다운 상태가 아닙니다.")]


def _handle_query_prometheus_range(
    promql: str,
    start_minutes_ago: int,
    end_minutes_ago: int,
    step_seconds: int,
) -> list[TextContent]:
    if not promql:
        return [TextContent(type="text", text="promql 파라미터가 필요합니다.")]

    now = time.time()
    start = now - start_minutes_ago * 60
    end   = now - end_minutes_ago * 60

    try:
        resp = requests.get(
            f"{config.PROMETHEUS_URL}/api/v1/query_range",
            params={"query": promql, "start": start, "end": end, "step": step_seconds},
            timeout=10,
        )
        resp.raise_for_status()
        result = resp.json().get("data", {}).get("result", [])

        if not result:
            return [TextContent(type="text", text=f"`{promql}` → 해당 구간 데이터 없음")]

        lines = [
            f"**{promql}** ({start_minutes_ago}분 전 ~ {end_minutes_ago}분 전)\n"
        ]
        for series in result:
            values = [float(v[1]) for v in series["value"] if v[1] != "NaN"] if "value" in series else \
                     [float(v[1]) for v in series.get("values", []) if v[1] != "NaN"]
            if not values:
                continue
            label_str = ", ".join(f'{k}="{v}"' for k, v in series.get("metric", {}).items()) or "(no labels)"
            lines.append(
                f"- {label_str}\n"
                f"  최솟값: {min(values):.2f} / 최대값: {max(values):.2f} / 평균: {sum(values)/len(values):.2f}"
            )

        return [TextContent(type="text", text="\n".join(lines))]

    except Exception as e:
        return [TextContent(type="text", text=f"Prometheus range 쿼리 실패: {e}")]


# 테스트 리포트에서 조회할 메트릭 목록 (promql, 레이블, 임계값)
_REPORT_METRICS = [
    (
        'sum(increase(http_server_requests_seconds_count{status=~"5.."}[1m]))',
        "5xx 에러 (1분 합산)",
        config.HTTP_5XX_THRESHOLD,
        "건",
    ),
    (
        "campaign_redis_queue_size",
        "Redis Queue 적재량",
        config.REDIS_QUEUE_CRITICAL,
        "건",
    ),
    (
        "sum(kafka_consumergroup_lag_sum)",
        "Kafka consumer lag",
        config.KAFKA_LAG_CRITICAL,
        "",
    ),
    (
        "campaign_consumer_latency_ms",
        "Consumer 처리 지연",
        config.CONSUMER_LATENCY_CRITICAL_MS,
        "ms",
    ),
    (
        "max(hikaricp_pending_threads)",
        "HikariCP pending",
        config.HIKARI_PENDING_THRESHOLD,
        "개",
    ),
    (
        "campaign_bridge_cycle_seconds",
        "Bridge 드레인 사이클",
        config.BRIDGE_CYCLE_WARNING_SECONDS,
        "초",
    ),
]


def _handle_get_test_report(start_minutes_ago: int, end_minutes_ago: int) -> list[TextContent]:
    now   = time.time()
    start = now - start_minutes_ago * 60
    end   = now - end_minutes_ago * 60
    step  = 30  # 30초 해상도

    lines = [
        f"## 부하 테스트 리포트",
        f"조회 구간: {start_minutes_ago}분 전 ~ {end_minutes_ago}분 전\n",
        f"{'메트릭':<30} {'최대값':>12} {'임계값':>12} {'초과':>6}",
        "-" * 65,
    ]

    for promql, label, threshold, unit in _REPORT_METRICS:
        try:
            resp = requests.get(
                f"{config.PROMETHEUS_URL}/api/v1/query_range",
                params={"query": promql, "start": start, "end": end, "step": step},
                timeout=10,
            )
            resp.raise_for_status()
            result = resp.json().get("data", {}).get("result", [])

            if not result:
                lines.append(f"{label:<30} {'데이터 없음':>12}")
                continue

            all_values = []
            for series in result:
                all_values += [float(v[1]) for v in series.get("values", []) if v[1] != "NaN"]

            if not all_values:
                lines.append(f"{label:<30} {'데이터 없음':>12}")
                continue

            max_val = max(all_values)
            exceeded = "⚠️ YES" if max_val > threshold else "OK"
            lines.append(
                f"{label:<30} {f'{max_val:.1f}{unit}':>12} {f'{threshold}{unit}':>12} {exceeded:>6}"
            )

        except Exception as e:
            lines.append(f"{label:<30} 조회 실패: {e}")

    lines.append("\n*CloudWatch 메트릭(앱 CPU, RDS CPU)은 query_prometheus_range로 별도 확인*")
    return [TextContent(type="text", text="\n".join(lines))]


def _handle_trigger_consistency_check() -> list[TextContent]:
    try:
        check_consistency()
        return [TextContent(type="text", text="정합성 검사 실행 완료 — Slack 결과 확인")]
    except Exception as e:
        return [TextContent(type="text", text=f"정합성 검사 실패: {e}")]


# ---------------------------------------------------------------------------
# FastAPI 마운트용 SSE transport
# ---------------------------------------------------------------------------

sse = SseServerTransport("/mcp/messages/")


async def handle_sse(request):
    async with sse.connect_sse(request.scope, request.receive, request._send) as streams:
        await mcp_server.run(streams[0], streams[1], mcp_server.create_initialization_options())
    return Response()


mcp_routes = [
    Route("/mcp/sse", endpoint=handle_sse, methods=["GET"]),
    Mount("/mcp/messages/", app=sse.handle_post_message),
]
