#!/usr/bin/env bash
# 기존 테스트 환경에서 새 캠페인을 생성하고 fixed-capacity integrity를 실행한다.
set -euo pipefail
source "$(dirname "$0")/live-common.sh"

if [[ $# -ne 0 || "${CONFIRM_RUN:-}" != yes ]]; then
  echo "사용법과 사전 조건: stress-test/harness/README.md (CONFIRM_RUN=yes 필요)" >&2
  exit 2
fi

: "${TOTAL_REQUESTS:?요청 수 필요}" "${MAX_VUS:?VU 수 필요}" "${DURATION:?maxDuration의 절반(초) 필요}"
: "${REDIS_GUARD_BYTES:?샤드당 Redis 메모리 중단 기준(bytes) 필요}"
: "${EXPECTED_APP_SHA:?배포한 Git SHA 필요}" "${START_CONDITION:?cold 또는 warmed 필요}"
DRAIN_TIMEOUT=${DRAIN_TIMEOUT:-900}
EXPECTED_APPS=${EXPECTED_APPS:-3}
REDIS_PRIMARIES=${REDIS_PRIMARIES:-3}
for key in TOTAL_REQUESTS MAX_VUS DURATION REDIS_GUARD_BYTES DRAIN_TIMEOUT EXPECTED_APPS REDIS_PRIMARIES; do
  [[ "${!key}" =~ ^[1-9][0-9]{0,9}$ ]] || { echo "$key: 양의 정수 필요" >&2; exit 2; }
done
[[ "$EXPECTED_APP_SHA" =~ ^[a-f0-9]{40}$ ]] || { echo "EXPECTED_APP_SHA: 전체 Git SHA 필요" >&2; exit 2; }
[[ "$START_CONDITION" == cold || "$START_CONDITION" == warmed ]] || exit 2
[[ "$AWS_REGION" == ap-northeast-2 ]] || { echo "ap-northeast-2 테스트 환경만 지원" >&2; exit 2; }
export EXPECTED_AWS_ACCOUNT_ID=631124976154 ALB_NAME=alb-batch-kafka-api
export AWS_PAGER=
export AWS_MAX_ATTEMPTS=2

SCRIPT_COMMIT=$(git -C "$HERE" rev-parse --verify "${LOADGEN_SHA:-origin/main}^{commit}")
HARNESS_COMMIT=$(git -C "$HERE" rev-parse HEAD)
git -C "$HERE/../.." diff --quiet HEAD -- stress-test/harness stress-test/resolve-base-url.sh ops/scripts/lib-ssm.sh ||
  { echo "하네스 파일을 커밋한 뒤 실행하세요." >&2; exit 2; }
RUN_ID="integrity-$(date -u +%Y%m%dT%H%M%S)-$$"
EVID="${EVIDENCE_ROOT:-$HERE/../../evidence}/$RUN_ID"
REMOTE_DIR="/var/tmp/campaign-harness/$RUN_ID"
mkdir -p "$(dirname "$EVID")"
mkdir -m 700 "$EVID"
echo "증거 디렉터리 $EVID"

python3 - "$EVID/run.json" "$RUN_ID" "$SCRIPT_COMMIT" "$HARNESS_COMMIT" "$EXPECTED_APP_SHA" "$START_CONDITION" \
  "$TOTAL_REQUESTS" "$MAX_VUS" "$DURATION" "$REDIS_GUARD_BYTES" "$DRAIN_TIMEOUT" "$EXPECTED_APPS" "$REDIS_PRIMARIES" <<'PY'
import json, sys
path, run_id, scripts, harness, app, startup, *ints = sys.argv[1:]
fields = ("total_requests", "max_vus", "duration", "redis_guard_bytes", "drain_timeout", "expected_apps", "redis_primaries")
with open(path, "w") as f:
    json.dump(dict(zip(fields, map(int, ints)), run_id=run_id, mode="integrity", purpose="final",
                   loadgen_sha=scripts, harness_sha=harness, expected_app_sha=app, start_condition=startup), f, indent=2)
PY

launched=false
reason=precheck
finish() {
  local code=$?
  trap - EXIT
  trap '' INT TERM
  set +e
  if [[ -n "$reason" ]]; then printf '%s\n' "$reason" > "$EVID/controller-interruption"; fi
  if $launched; then
    if [[ -n "$reason" ]]; then
      # RuntimeMaxSec가 원격 실행의 상한이다. 종료 명령도 해당 실행 하나에만 적용한다.
      ssm_run "$MCP" "systemctl stop $RUN_ID.service" >/dev/null 2>&1 || true
    fi
    bash "$HERE/collect.sh" "$EVID" "$MCP" "$BASE_URL" "$REMOTE_DIR"
    if [[ $? != 0 ]]; then echo collector >> "$EVID/collect-errors.txt"; fi
  fi
  python3 "$HERE/verdict.py" "$EVID"
  local verdict_code=$?
  echo "결과 $EVID/verdict.md"
  if (( code != 0 )); then exit "$code"; fi
  exit "$verdict_code"
}
trap finish EXIT
trap 'reason=operator-interruption; exit 130' INT TERM

BASE_URL=$(bash "$HERE/../resolve-base-url.sh")
[[ "$BASE_URL" =~ ^http://[a-zA-Z0-9.-]+\.elb\.amazonaws\.com$ ]]
MCP=$(mcp_instance_id)
[[ "$MCP" =~ ^i-[0-9a-f]+$ ]]
REDIS_HOST=$(redis_host)
[[ "$REDIS_HOST" =~ ^[a-zA-Z0-9.-]+$ && "$REDIS_HOST" != pending ]]
echo "사전 점검 중  terraform-mcp=$MCP"
bash "$HERE/precheck.sh" "$EVID" "$MCP" "$BASE_URL"

# 부하 캠페인은 새로 생성한다. 기존 캠페인에 같은 요청을 재전송하지 않는다.
reason=campaign-creation
curl --max-time 30 -fsS -X POST "$BASE_URL/api/admin/campaigns" -H 'Content-Type: application/json' \
  -d "{\"name\":\"$RUN_ID\",\"totalStock\":$TOTAL_REQUESTS}" > "$EVID/campaign.json"
CAMPAIGN_ID=$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); assert d["success"] is True; print(d["data"]["id"])' "$EVID/campaign.json")
[[ "$CAMPAIGN_ID" =~ ^[1-9][0-9]*$ ]]
printf '%s\n' "$CAMPAIGN_ID" > "$EVID/campaign-id"

reason=remote-launch
RUNTIME_LIMIT=$((DURATION * 2 + DRAIN_TIMEOUT + 180))
remote=$(cat <<REMOTE
set -eu
umask 077
mkdir -p /var/tmp/campaign-harness
mkdir $REMOTE_DIR
cd $REMOTE_DIR
cat > run.env <<'CONFIG'
$(for key in RUN_ID LOADGEN_REPO LAG REDIS_HOST REDIS_PRIMARIES REDIS_GUARD_BYTES CAMPAIGN_ID TOTAL_REQUESTS MAX_VUS DURATION BASE_URL DRAIN_TIMEOUT; do
    printf '%s=%q\n' "$key" "${!key}"
  done)
CONFIG
cat > remote-run.sh <<'SCRIPT'
$(cat "$HERE/remote-run.sh")
SCRIPT
systemd-run --unit=$RUN_ID --property=WorkingDirectory=$REMOTE_DIR --property=RuntimeMaxSec=$RUNTIME_LIMIT --property=TimeoutStopSec=30s /bin/bash $REMOTE_DIR/remote-run.sh
REMOTE
)
launched=true
ssm_run "$MCP" "$remote"
echo "캠페인 $CAMPAIGN_ID 부하 실행 중"
reason=remote-watch
deadline=$((SECONDS + RUNTIME_LIMIT + 30))
while (( SECONDS < deadline )); do
  state=$(ssm_run "$MCP" "if test -s $REMOTE_DIR/drain-end; then echo finished; else systemctl is-active $RUN_ID.service || true; fi")
  case "$state" in
    finished) reason=; exit 0 ;;
    active|activating) sleep 15 ;;
    *) echo "원격 실행 종료 상태 $state" >&2; exit 2 ;;
  esac
done
reason=controller-timeout
exit 2
