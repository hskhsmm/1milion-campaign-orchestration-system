#!/usr/bin/env bash
# 부하 테스트 중 캠페인의 Redis 상태를 1초마다 기록한다 (terraform-mcp에서 컨테이너로 실행).
#
# 사용법: ./ops/scripts/redis-watch-start.sh <campaignId>
#
# 기록 항목 (KST):
#   inSet = SISMEMBER active:campaigns <id>   (Bridge 순회 대상 여부)
#   flag  = EXISTS active:campaign:{id}       (신규 요청 차단 플래그)
#   llen  = LLEN queue:campaign:{id}          (큐 잔량)
#   stock = GET stock:campaign:{id}
#   set   = SMEMBERS active:campaigns
# 결과는 redis-watch-fetch.sh로 가져온다.
set -euo pipefail
source "$(dirname "$0")/lib-ssm.sh"

CAMPAIGN_ID="${1:?사용법: $0 <campaignId>}"
[[ "$CAMPAIGN_ID" =~ ^[0-9]+$ ]] || { echo "campaignId는 숫자여야 합니다: $CAMPAIGN_ID" >&2; exit 1; }

MCP_ID=$(mcp_instance_id)
REDIS_HOST=$(redis_host)
echo "terraform-mcp=$MCP_ID redis=$REDIS_HOST campaign=$CAMPAIGN_ID"

# busybox date는 tzdata 없이 POSIX TZ(KST-9)로 KST를 출력한다.
LOOP='while true; do
  ts=$(TZ=KST-9 date "+%F %T")
  inset=$(redis-cli -c -h "$H" SISMEMBER active:campaigns "$C" 2>&1)
  flag=$(redis-cli -c -h "$H" EXISTS "active:campaign:{$C}" 2>&1)
  llen=$(redis-cli -c -h "$H" LLEN "queue:campaign:{$C}" 2>&1)
  stock=$(redis-cli -c -h "$H" GET "stock:campaign:{$C}" 2>&1)
  members=$(redis-cli -c -h "$H" SMEMBERS active:campaigns 2>&1 | sort -n | tr "\n" "," )
  echo "$ts inSet=$inset flag=$flag llen=$llen stock=$stock set=[$members]"
  sleep 1
done'

REMOTE="set -eu
docker rm -f redis-watch >/dev/null 2>&1 || true
docker run -d --name redis-watch --network host -e H='$REDIS_HOST' -e C='$CAMPAIGN_ID' redis:7-alpine sh -c '$LOOP'
sleep 3
docker logs --tail 3 redis-watch"

OUT=$(ssm_run "$MCP_ID" "$REMOTE")
echo "$OUT"
if grep -qiE "Could not connect|error|refused" <<<"$OUT"; then
  echo "Redis 조회 실패. 감시 컨테이너 출력을 확인하세요." >&2
  exit 1
fi
echo "감시 시작됨. 테스트 종료 후 ./ops/scripts/redis-watch-fetch.sh 실행"
