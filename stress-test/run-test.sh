#!/bin/bash
# k6 부하 테스트 실행 스크립트
# 사용법: ./run-test.sh [환경] [모드]
#
# 환경:
#   local  — localhost:8080 (기본값)
#   prod   — AWS ALB
# 모드:
#   integrity — 정확한 요청 수 기반 정합성/Spike 검증 (기본값)
#   capacity  — arrival-rate 기반 지속 가능 처리량 검증
#
# 예시:
#   ./run-test.sh local
#   ./run-test.sh prod
#   CAMPAIGN_ID=2 TOTAL_REQUESTS=30000 ./run-test.sh prod
#   RPS_STAGES=1000,2000,3000,4000 ./run-test.sh prod capacity

set -euo pipefail

ENV=${1:-"local"}
MODE=${2:-"integrity"}

# 환경별 BASE_URL
if [ "$ENV" = "prod" ]; then
  BASE_URL="http://alb-batch-kafka-api-1351817547.ap-northeast-2.elb.amazonaws.com"
else
  BASE_URL="http://localhost:8080"
fi

case "$MODE" in
  integrity)
    CAMPAIGN_ID=${CAMPAIGN_ID:-1}
    TOTAL_REQUESTS=${TOTAL_REQUESTS:-15000}
    MAX_VUS=${MAX_VUS:-1000}
    DURATION=${DURATION:-60}
    USER_ID_OFFSET=${USER_ID_OFFSET:-0}
    LOG_EVERY=${LOG_EVERY:-10000}
    REQUEST_TIMEOUT=${REQUEST_TIMEOUT:-30s}

    echo "=============================="
    echo " k6 integrity/spike test start"
    echo " ENV           : $ENV"
    echo " BASE_URL      : $BASE_URL"
    echo " CAMPAIGN_ID   : $CAMPAIGN_ID"
    echo " TOTAL_REQUESTS: $TOTAL_REQUESTS"
    echo " MAX_VUS       : $MAX_VUS"
    echo " MAX_DURATION  : $((DURATION * 2))s"
    echo " REQUEST_TIMEOUT: $REQUEST_TIMEOUT"
    echo "=============================="

    k6 run \
      -e BASE_URL="$BASE_URL" \
      -e CAMPAIGN_ID="$CAMPAIGN_ID" \
      -e TOTAL_REQUESTS="$TOTAL_REQUESTS" \
      -e MAX_VUS="$MAX_VUS" \
      -e DURATION="$DURATION" \
      -e USER_ID_OFFSET="$USER_ID_OFFSET" \
      -e LOG_EVERY="$LOG_EVERY" \
      -e REQUEST_TIMEOUT="$REQUEST_TIMEOUT" \
      "$(dirname "$0")/k6-load-test.js"
    ;;

  capacity)
    TARGET_RPS=${TARGET_RPS:-3000}
    START_RPS=${START_RPS:-50}
    RPS_STAGES=${RPS_STAGES:-}
    PRE_ALLOCATED_VUS=${PRE_ALLOCATED_VUS:-2000}
    MAX_VUS=${MAX_VUS:-10000}
    WARMUP_SECONDS=${WARMUP_SECONDS:-10}
    STEADY_SECONDS=${STEADY_SECONDS:-30}
    COOLDOWN_SECONDS=${COOLDOWN_SECONDS:-5}
    STAGE_SECONDS=${STAGE_SECONDS:-60}
    STEP_RAMP_SECONDS=${STEP_RAMP_SECONDS:-15}
    P95_MS=${P95_MS:-1000}
    MAX_FAIL_RATE=${MAX_FAIL_RATE:-0.01}
    CAMPAIGN_STOCK=${CAMPAIGN_STOCK:-9999999}

    echo "=============================="
    echo " k6 capacity test start"
    echo " ENV              : $ENV"
    echo " BASE_URL         : $BASE_URL"
    echo " TARGET_RPS       : $TARGET_RPS"
    echo " START_RPS        : $START_RPS"
    echo " RPS_STAGES       : ${RPS_STAGES:-single target}"
    echo " PRE_ALLOCATED_VUS: $PRE_ALLOCATED_VUS"
    echo " MAX_VUS          : $MAX_VUS"
    echo " WARMUP/STEADY    : ${WARMUP_SECONDS}s / ${STEADY_SECONDS}s"
    echo " STAGE/RAMP       : ${STAGE_SECONDS}s / ${STEP_RAMP_SECONDS}s"
    echo " P95_SLO          : ${P95_MS}ms"
    echo "=============================="

    k6 run \
      -e BASE_URL="$BASE_URL" \
      -e TARGET_RPS="$TARGET_RPS" \
      -e START_RPS="$START_RPS" \
      -e RPS_STAGES="$RPS_STAGES" \
      -e PRE_ALLOCATED_VUS="$PRE_ALLOCATED_VUS" \
      -e MAX_VUS="$MAX_VUS" \
      -e WARMUP_SECONDS="$WARMUP_SECONDS" \
      -e STEADY_SECONDS="$STEADY_SECONDS" \
      -e COOLDOWN_SECONDS="$COOLDOWN_SECONDS" \
      -e STAGE_SECONDS="$STAGE_SECONDS" \
      -e STEP_RAMP_SECONDS="$STEP_RAMP_SECONDS" \
      -e P95_MS="$P95_MS" \
      -e MAX_FAIL_RATE="$MAX_FAIL_RATE" \
      -e CAMPAIGN_STOCK="$CAMPAIGN_STOCK" \
      "$(dirname "$0")/k6-tps-test.js"
    ;;

  *)
    echo "Unknown mode: $MODE (expected: integrity or capacity)" >&2
    exit 2
    ;;
esac
