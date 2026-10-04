#!/usr/bin/env bash
# redis-watch-start.sh 기록을 로컬로 가져오고, 캠페인이 active Set에서 빠진 시점을 요약한다.
#
# 사용법: ./ops/scripts/redis-watch-fetch.sh [--stop] [출력 디렉터리]
#   --stop  가져온 뒤 감시 컨테이너를 종료한다.
set -euo pipefail
source "$(dirname "$0")/lib-ssm.sh"

STOP=false
if [[ "${1:-}" == "--stop" ]]; then STOP=true; shift; fi
OUT_DIR="${1:-evidence/$(date +%Y%m%d)}"
mkdir -p "$OUT_DIR"

MCP_ID=$(mcp_instance_id)
ssm_fetch "$MCP_ID" "docker logs redis-watch" "$OUT_DIR/redis-watch.log"
$STOP && ssm_run "$MCP_ID" "docker rm -f redis-watch" >/dev/null && echo "감시 컨테이너 종료"

LOG="$OUT_DIR/redis-watch.log"
echo "저장: $LOG ($(wc -l < "$LOG") 줄)"
echo
echo "== 상태 변화 지점 (inSet / flag 변화, llen은 그 시점 값)"
awk '{
  inset=$3; flag=$4
  if (inset != prev_inset || flag != prev_flag) { print; prev_inset=inset; prev_flag=flag }
} END { print "-- 마지막 기록"; print }' "$LOG"
