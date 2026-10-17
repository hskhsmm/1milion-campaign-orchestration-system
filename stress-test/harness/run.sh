#!/usr/bin/env bash
# 실환경 부하 테스트 1회를 정해진 순서로 실행하고 판정까지 남긴다.
#   사전 점검 → (실패 시 중단) → Queue 감시 시작 → k6 → drain 대기 → 증거 수집 → 판정
#
# 비용과 실환경 상태에 영향을 주는 명령이다. 대상 계정·리전·환경을 확인한 사람이 직접 실행한다.
# 데이터 삭제, 배포, Terraform 변경, env-down은 하지 않는다.
#
# 사용법 (운영 PC, 저장소 루트):
#   MODE=integrity PURPOSE=final TOTAL_REQUESTS=10000 MAX_VUS=200 bash stress-test/harness/run.sh
#   MODE=capacity  PURPOSE=explore TARGET_RPS=1000 STEADY_SECONDS=600 bash stress-test/harness/run.sh
#
# 환경 변수
#   MODE            integrity | capacity (필수)
#   PURPOSE         explore | final | fault (기본 explore). final은 PASS/FAIL을 낸다.
#                   fault는 일부러 장애를 낸 실행이다. 장애 주입은 이 스크립트 밖에서 사람이 한다.
#   FAULT_DESC      fault일 때 필수: 주입한 장애 설명
#   FAULT_EXPECT    fault일 때 필수: 잡혀야 할 항목과 상태 (예: "k6_errors=FAIL,k6_target=FAIL")
#   RUN_LABEL       RUN_ID 뒤에 붙일 이름 (기본: MODE)
#   DRAIN_TIMEOUT   부하 종료 후 LLEN·lag 0을 기다릴 최대 초 (기본 600)
#   CRITERIA_FILE   capacity final의 사전 합의 기준 JSON ({"p95_ms":..,"max_fail_rate":..})
#   integrity: TOTAL_REQUESTS(필수), MAX_VUS(기본 1000), DURATION(기본 3600)
#   capacity : TARGET_RPS(필수), START_RPS(50), WARMUP_SECONDS(30), STEADY_SECONDS(600),
#              COOLDOWN_SECONDS(5), PRE_ALLOCATED_VUS(TARGET_RPS), MAX_VUS(3000)
# 종료 코드: 0 PASS/VALID/FAULT_DETECTED, 1 FAIL/FAULT_MISSED, 2 INCONCLUSIVE/FAULT_UNEXPECTED,
#           3 사전 점검 실패로 중단 (fault가 아닐 때)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$ROOT"
source ops/scripts/lib-ssm.sh

MODE="${MODE:?MODE=integrity|capacity 필요}"
PURPOSE="${PURPOSE:-explore}"
DRAIN_TIMEOUT="${DRAIN_TIMEOUT:-600}"
num() { [[ "$2" =~ ^[0-9]+$ ]] || { echo "$1는 숫자여야 합니다: $2" >&2; exit 2; }; }
[[ "$PURPOSE" =~ ^(explore|final|fault)$ ]] || { echo "PURPOSE는 explore|final|fault: $PURPOSE" >&2; exit 2; }
if [[ "$PURPOSE" == fault ]]; then
  : "${FAULT_DESC:?fault는 FAULT_DESC 필요}" "${FAULT_EXPECT:?fault는 FAULT_EXPECT 필요 (예: k6_errors=FAIL)}"
  [[ "$FAULT_EXPECT" =~ ^[a-z0-9_]+=(FAIL|INCONCLUSIVE)(,[a-z0-9_]+=(FAIL|INCONCLUSIVE))*$ ]] \
    || { echo "FAULT_EXPECT 형식: id=FAIL|INCONCLUSIVE[,...]: $FAULT_EXPECT" >&2; exit 2; }
fi
num DRAIN_TIMEOUT "$DRAIN_TIMEOUT"

case "$MODE" in
  integrity)
    TOTAL_REQUESTS="${TOTAL_REQUESTS:?integrity는 TOTAL_REQUESTS 필요}"
    MAX_VUS="${MAX_VUS:-1000}"; DURATION="${DURATION:-3600}"
    for v in TOTAL_REQUESTS MAX_VUS DURATION; do num "$v" "${!v}"; done
    ;;
  capacity)
    TARGET_RPS="${TARGET_RPS:?capacity는 TARGET_RPS 필요}"
    START_RPS="${START_RPS:-50}"; WARMUP_SECONDS="${WARMUP_SECONDS:-30}"; STEADY_SECONDS="${STEADY_SECONDS:-600}"
    COOLDOWN_SECONDS="${COOLDOWN_SECONDS:-5}"; PRE_ALLOCATED_VUS="${PRE_ALLOCATED_VUS:-$TARGET_RPS}"; MAX_VUS="${MAX_VUS:-3000}"
    for v in TARGET_RPS START_RPS WARMUP_SECONDS STEADY_SECONDS COOLDOWN_SECONDS PRE_ALLOCATED_VUS MAX_VUS; do num "$v" "${!v}"; done
    ;;
  *) echo "MODE는 integrity|capacity: $MODE" >&2; exit 2 ;;
esac

RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-${RUN_LABEL:-$MODE}"
EVID="evidence/$RUN_ID"
mkdir -p "$EVID"
[[ -n "${CRITERIA_FILE:-}" ]] && cp "$CRITERIA_FILE" "$EVID/criteria.json"
echo "RUN_ID=$RUN_ID"

# run.json 갱신: set_run key=value ... (값은 JSON으로 해석되면 그대로, 아니면 문자열)
set_run() {
  python3 - "$EVID/run.json" "$@" <<'PY'
import json, sys
from pathlib import Path
p = Path(sys.argv[1]); doc = json.loads(p.read_text()) if p.exists() else {}
for kv in sys.argv[2:]:
    k, v = kv.split("=", 1)
    try: doc[k] = json.loads(v)
    except json.JSONDecodeError: doc[k] = v
p.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n")
PY
}
STAGE="준비"
FINISHED=false
finish() {
  local code=0
  FINISHED=true
  python3 "$HERE/verdict.py" "$EVID" || code=$?
  echo "증거: $EVID (판정: $EVID/verdict.md)"
  exit "$code"
}
# 중간에 실패하거나 중단돼도 판정 없이 끝나지 않게 한다:
# 수집 오류를 남기고, 부하를 시작한 뒤라면 가능한 증거를 모아 판정한다(INCONCLUSIVE).
# Queue 감시 컨테이너는 수집 단계에서 종료된다. k6는 부하 발생기에서 계속 돌 수 있으므로 안내만 한다.
on_exit() {
  local code=$?
  $FINISHED && return
  trap - EXIT INT TERM
  echo "run.sh 중단: 단계=$STAGE, 종료 코드=$code" >&2
  echo "run.sh: '$STAGE' 단계에서 중단 (종료 코드 $code)" >> "$EVID/collect-errors.txt"
  if [[ "$STAGE" != "준비" && "$STAGE" != "사전 점검" ]]; then
    bash "$HERE/collect.sh" "$EVID" || true
    echo "run.sh: '$STAGE' 단계에서 중단 (종료 코드 $code)" >> "$EVID/collect-errors.txt"
    echo "주의: 부하 발생기의 k6가 아직 실행 중일 수 있다. 확인: ssm_run \$(mcp_instance_id) 'pgrep -af \"k6 run\"'" >&2
  fi
  python3 "$HERE/verdict.py" "$EVID" || true
  exit "$code"
}
trap on_exit EXIT
trap 'exit 130' INT TERM

set_run run_id="$RUN_ID" mode="$MODE" purpose="$PURPOSE" drain_timeout="$DRAIN_TIMEOUT"
if [[ "$PURPOSE" == fault ]]; then
  set_run fault="$(python3 -c 'import json,sys; print(json.dumps({"description": sys.argv[1], "expected": dict(kv.split("=") for kv in sys.argv[2].split(","))}, ensure_ascii=False))' "$FAULT_DESC" "$FAULT_EXPECT")"
fi
if [[ "$MODE" == integrity ]]; then
  set_run total_requests="$TOTAL_REQUESTS" max_vus="$MAX_VUS"
else
  set_run target_rps="$TARGET_RPS" warmup_seconds="$WARMUP_SECONDS" steady_seconds="$STEADY_SECONDS" max_vus="$MAX_VUS"
fi

# ---- 1. 사전 점검 ----
STAGE="사전 점검"
echo "== 사전 점검"
bash "$HERE/precheck.sh" "$EVID"
pre_fail=$(python3 - "$EVID" "$HERE" <<'PY'
import sys
from pathlib import Path
sys.path.insert(0, sys.argv[2])
import verdict
r = verdict.evaluate(Path(sys.argv[1]))
print(",".join(c["id"] for c in r["checks"] if c["id"].startswith("pre") and c["status"] == "FAIL"))
PY
)
if [[ -n "$pre_fail" ]]; then
  echo "사전 점검 실패($pre_fail): 부하를 시작하지 않습니다." >&2
  FINISHED=true
  set_run aborted="$pre_fail"
  code=0; python3 "$HERE/verdict.py" "$EVID" || code=$?
  [[ "$PURPOSE" == fault ]] && exit "$code"
  exit 3
fi

MCP=$(mcp_instance_id)
LOADGEN=/home/ssm-user/1milion-campaign-orchestration-system/stress-test
R="$LOADGEN/evidence/$RUN_ID"
BASE_URL=$(bash stress-test/resolve-base-url.sh)

launch_k6() { # launch_k6 <환경 변수 목록>
  ssm_run "$MCP" "cd $LOADGEN && sudo -u ssm-user mkdir -p evidence/$RUN_ID && sudo -u ssm-user bash -c 'unset BASE_URL; export K6_SUMMARY_EXPORT=evidence/$RUN_ID/k6-summary.json $1; nohup bash ./run-test.sh prod $MODE > evidence/$RUN_ID/k6-run.log 2>&1 &'" > /dev/null
}

# ---- 2. Queue 감시 + k6 ----
STAGE="캠페인 생성·k6 시작"
if [[ "$MODE" == integrity ]]; then
  CID=$(curl -fsS -X POST "$BASE_URL/api/admin/campaigns" -H 'Content-Type: application/json' \
    -d "{\"name\":\"harness-$RUN_ID\",\"totalStock\":$TOTAL_REQUESTS}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["id"])')
  num campaign_id "$CID"
  echo "== 캠페인 $CID 생성, Queue 감시 시작"
  bash ops/scripts/redis-watch-start.sh "$CID" | tail -1
  set_run campaign_id="$CID" load_start_epoch="$(date +%s)"
  launch_k6 "CAMPAIGN_ID=$CID TOTAL_REQUESTS=$TOTAL_REQUESTS MAX_VUS=$MAX_VUS DURATION=$DURATION"
  MAX_WAIT=$(( DURATION * 2 + 120 ))
else
  set_run load_start_epoch="$(date +%s)"
  launch_k6 "TARGET_RPS=$TARGET_RPS START_RPS=$START_RPS WARMUP_SECONDS=$WARMUP_SECONDS STEADY_SECONDS=$STEADY_SECONDS COOLDOWN_SECONDS=$COOLDOWN_SECONDS PRE_ALLOCATED_VUS=$PRE_ALLOCATED_VUS MAX_VUS=$MAX_VUS"
  # k6 setup이 캠페인을 만든다. ID를 읽은 즉시 감시를 시작한다(워밍업 안에 시작돼야 판정이 유효).
  CID=""
  for _ in $(seq 1 30); do
    CID=$(ssm_run "$MCP" "grep -m1 -o 'ID=[0-9]*' $R/k6-run.log 2>/dev/null | cut -d= -f2" | tr -d '[:space:]') || true
    [[ -n "$CID" ]] && break
    sleep 2
  done
  num campaign_id "$CID"
  echo "== k6 setup 캠페인 $CID, Queue 감시 시작"
  bash ops/scripts/redis-watch-start.sh "$CID" | tail -1
  setup_epoch=$(ssm_run "$MCP" "grep -m1 'Setup\\] 캠페인 생성' $R/k6-run.log | grep -o 'time=\"[^\"]*\"' | cut -d'\"' -f2" | tr -d '[:space:]' \
    | python3 -c 'import sys,datetime; print(int(datetime.datetime.fromisoformat(sys.stdin.read().replace("Z","+00:00")).timestamp()))')
  set_run campaign_id="$CID" load_start_epoch="$setup_epoch" steady_start_epoch="$(( setup_epoch + WARMUP_SECONDS ))"
  MAX_WAIT=$(( WARMUP_SECONDS + STEADY_SECONDS + COOLDOWN_SECONDS + 300 ))
fi

# ---- 3. k6 종료 대기 ----
STAGE="k6 대기"
echo "== k6 실행 중 (최대 ${MAX_WAIT}초 대기)"
waited=0
until ssm_run "$MCP" "test -f $R/k6-summary.json && ! pgrep -u ssm-user -f 'k6 run' >/dev/null && echo done; true" | grep -q done; do
  (( waited >= MAX_WAIT )) && { echo "k6 대기 시간 초과" >&2; echo "k6: 대기 시간 초과" >> "$EVID/collect-errors.txt"; break; }
  sleep 15; waited=$(( waited + 15 ))
done
set_run load_end_epoch="$(date +%s)"

# ---- 4. drain 대기: LLEN과 Kafka lag가 모두 0이 될 때까지 ----
STAGE="drain 대기"
echo "== drain 대기 (최대 ${DRAIN_TIMEOUT}초)"
H=$(redis_host)
drain_start=$(date +%s); drained=false; llen=""; lag=""
while :; do
  out=$(ssm_run "$MCP" "docker run --rm redis:7-alpine redis-cli -c -h $H LLEN 'queue:campaign:{$CID}'; curl -fsSG localhost:9090/api/v1/query --data-urlencode 'query=sum(kafka_consumergroup_lag{consumergroup=\"campaign-participation-group\"})'" || true)
  llen=$(echo "$out" | head -1 | tr -d '[:space:]')
  lag=$(echo "$out" | tail -1 | python3 -c 'import json,sys
try: print(int(float(json.load(sys.stdin)["data"]["result"][0]["value"][1])))
except Exception: print("")')
  [[ "$llen" == 0 && "$lag" == 0 ]] && { drained=true; break; }
  (( $(date +%s) - drain_start >= DRAIN_TIMEOUT )) && break
  sleep 5
done
waited_drain=$(( $(date +%s) - drain_start ))
python3 -c 'import json,sys; json.dump({"drained": sys.argv[1]=="true", "waited_seconds": int(sys.argv[2]), "last_llen": sys.argv[3], "last_lag": sys.argv[4]}, open(sys.argv[5],"w"))' \
  "$drained" "$waited_drain" "$llen" "$lag" "$EVID/drain.json"
set_run drain_end_epoch="$(date +%s)"
echo "drain: $drained (${waited_drain}초, LLEN=$llen lag=$lag)"

# ---- 5. 증거 수집 → 6. 판정 ----
STAGE="증거 수집"
echo "== 증거 수집"
bash "$HERE/collect.sh" "$EVID"
echo "== 판정"
finish
