#!/usr/bin/env bash
# 앱 ASG의 모든 인스턴스에서 컨테이너 로그 전체를 로컬로 가져온다.
# 인스턴스가 종료되면 로그가 사라지므로 make env-down 전에 반드시 실행한다.
#
# 사용법: [INSTANCE_IDS="i-a i-b"] ./ops/scripts/collect-app-logs.sh [출력 디렉터리]
set -euo pipefail
source "$(dirname "$0")/lib-ssm.sh"

OUT_DIR="${1:-evidence/$(date +%Y%m%d)}"
mkdir -p "$OUT_DIR"

IDS="${INSTANCE_IDS:-$(app_instance_ids)}"  # INSTANCE_IDS="i-a i-b" 로 대상 지정 가능
[[ -n "$IDS" ]] || { echo "$APP_ASG에 인스턴스가 없습니다." >&2; exit 1; }

for id in $IDS; do
  echo "== $id"
  # 인스턴스 기동 시각과 컨테이너 시작 시각도 같이 남긴다 (새 JVM 합류 시점 확인용).
  ssm_run "$id" "echo boot=\$(uptime -s); docker inspect -f 'container_started={{.State.StartedAt}} image={{.Config.Image}}' campaign-core-app" \
    | tee "$OUT_DIR/app-$id.meta"
  ssm_fetch "$id" "docker logs --timestamps campaign-core-app" "$OUT_DIR/app-$id.log"
done

echo
echo "== 핵심 로그 (인스턴스별)"
for f in "$OUT_DIR"/app-*.log; do
  echo "-- $(basename "$f")"
  grep -E "Campaign drained and deactivated|Deactivation skipped|Failed to drain campaign queue|Kafka publish completed with failure|Bridge message moved to DLQ|Started CampaignCoreApplication|RedisCommandTimeout|RedisConnectionFailure|MOVED|ASK " "$f" \
    | head -50 || echo "(없음)"
done
