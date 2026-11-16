#!/usr/bin/env bash
# 실행별 파일과 읽기 전용 확인 결과를 수집한다. dry-run은 별도 실행 기록을 남긴다.
set -uo pipefail
source "$(dirname "$0")/live-common.sh"

EVID=$1 MCP=$2 BASE_URL=$3 REMOTE_DIR=$4
CAMPAIGN_ID=$(cat "$EVID/campaign-id") || exit 1
[[ "$CAMPAIGN_ID" =~ ^[1-9][0-9]*$ ]] || exit 1
RUN_ID=$(json_field "$EVID/run.json" run_id) || exit 1
mkdir -p "$EVID/prometheus"
: > "$EVID/collect-errors.txt"

step() {
  local name="$1"; shift
  if ! "$@"; then
    printf '%s\n' "$name" >> "$EVID/collect-errors.txt"
    printf '수집 실패 %s\n' "$name" >&2
  fi
}

# 같은 쉘에서 ssm_fetch를 동시에 호출하면 공용 임시 파일을 공유한다.
for file in load-start load-end k6-exit drain-end drain.json interruption; do
  ssm_run "$MCP" "if test -f $REMOTE_DIR/$file; then cat $REMOTE_DIR/$file; fi" > "$EVID/$file" ||
    printf '%s\n' "$file" >> "$EVID/collect-errors.txt"
done
for file in k6-summary.json k6-run.log redis-watch.log redis-memory.tsv; do
  step "$file" ssm_fetch "$MCP" "cat $REMOTE_DIR/$file" "$EVID/$file"
done
python3 - "$EVID" <<'PY'
import json, sys
from pathlib import Path
root = Path(sys.argv[1])
path = root / "run.json"
run = json.loads(path.read_text())
for name, field in (("load-start", "load_start_epoch"), ("load-end", "load_end_epoch"),
                    ("drain-end", "drain_end_epoch"), ("k6-exit", "k6_exit_code")):
    value = (root / name).read_text().strip()
    if value.isdecimal():
        run[field] = int(value)
path.write_text(json.dumps(run, ensure_ascii=False, indent=2) + "\n")
PY

# drain-end가 없으면 실행을 완료하지 못했다. 다운로드된 부분 증거는 남긴다.
[[ -s "$EVID/drain-end" ]] || exit 1

prom_range() {
  local start end command
  start=$(json_field "$EVID/run.json" load_start_epoch) || return 1
  end=$(json_field "$EVID/run.json" drain_end_epoch) || return 1
  command=$(prom_command query_range "$2")
  printf '%s\t%s\t%s\t10\t%s\n' "$1" "$start" "$end" "$2" >> "$EVID/prometheus/queries.tsv"
  ssm_fetch "$MCP" "$command --data-urlencode start=$start --data-urlencode end=$end --data-urlencode step=10" "$EVID/prometheus/$1.json" &&
    python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); assert d["status"]=="success" and d["data"]["result"]' "$EVID/prometheus/$1.json"
}

prom_final() {
  local end
  end=$(json_field "$EVID/run.json" drain_end_epoch) || return 1
  printf '%s\t%s\t%s\n' "$1" "$end" "$2" >> "$EVID/prometheus/instant-queries.tsv"
  ssm_run "$MCP" "$(prom_command query "$2") --data-urlencode time=$end" > "$EVID/$1.json"
}

verify_loadgen() {
  ssm_run "$MCP" "set -e
export PATH=/usr/local/bin:\$PATH
cd $LOADGEN_REPO
git -c safe.directory=$LOADGEN_REPO rev-parse HEAD
k6 version
cd stress-test
md5sum k6-load-test.js k6-tps-test.js run-test.sh" > "$EVID/loadgen-after.txt" &&
    cmp -s "$EVID/loadgen-before.txt" "$EVID/loadgen-after.txt"
}

verify_apps() {
  local apps workers
  apps=$(app_instance_ids "$APP_ASG") || return 1
  workers=$(app_instance_ids "$WORKER_ASG") || return 1
  [[ -n "$apps" && -z "$workers" ]] || return 1
  read -ra ids <<< "$apps"
  app_snapshot "${ids[@]}" > "$EVID/apps-after.txt" || return 1
  diff -q <(sort "$EVID/apps-before.txt") <(sort "$EVID/apps-after.txt")
}

scaling() {
  aws_ autoscaling describe-scaling-activities --auto-scaling-group-name "$APP_ASG" \
    --query 'Activities[].{StartTime:StartTime,Description:Description}' --output json > "$EVID/scaling.json" &&
  aws_ autoscaling describe-scaling-activities --auto-scaling-group-name "$WORKER_ASG" \
    --query 'Activities[].{StartTime:StartTime,Description:Description}' --output json > "$EVID/worker-scaling.json"
}

status_api() {
  curl --max-time 15 -fsS "$BASE_URL/api/campaigns/$CAMPAIGN_ID/status" > "$EVID/status.json"
}

db_counts() {
  local inst sql remote
  inst=$(awk 'NR==1 {print $1}' "$EVID/apps-before.txt") || return 1
  [[ "$inst" =~ ^i-[0-9a-f]+$ ]] || return 1
  sql="SELECT UTC_TIMESTAMP(6) AS checked_utc;
SELECT COUNT(*) AS total_rows, COALESCE(SUM(status='SUCCESS'),0) AS success_count,
COUNT(*)-COUNT(DISTINCT sequence) AS dup_sequence_rows, COUNT(*)-COUNT(DISTINCT user_id) AS dup_user_rows,
MIN(sequence) AS first_sequence, MAX(sequence) AS last_sequence FROM participation_history WHERE campaign_id=$CAMPAIGN_ID;
SELECT COUNT(*) AS dlq_rows FROM dlq_message WHERE campaign_id=$CAMPAIGN_ID;"
  remote=$(cat <<REMOTE
set -e
env_get() { grep -m1 "^\$1=" /opt/campaign-core/.env.prod | cut -d= -f2-; }
url="\$(env_get SPRING_DATASOURCE_URL)"; url="\${url#jdbc:mysql://}"
hostport="\${url%%/*}"; db="\${url#*/}"; db="\${db%%\?*}"
host="\${hostport%%:*}"; port="\${hostport##*:}"
if [ "\$port" = "\$host" ]; then port=3306; fi
docker run --rm -e MYSQL_PWD="\$(env_get SPRING_DATASOURCE_PASSWORD)" mysql:8.4 \
  mysql --connect-timeout=10 -h "\$host" -P "\$port" -u "\$(env_get SPRING_DATASOURCE_USERNAME)" "\$db" --table -e "$sql"
REMOTE
)
  { printf 'instance=%s\nsql=%s\n' "$inst" "$sql"; ssm_run "$inst" "$remote"; } > "$EVID/db-counts.txt"
}

consistency() {
  local base id status deadline
  base="$BASE_URL/api/admin/consistency-recovery"
  curl --max-time 15 -fsS -X POST "$base" -H 'Content-Type: application/json' \
    -d "{\"requestedBy\":\"harness-$RUN_ID\",\"dryRun\":true,\"autoFix\":false,\"campaignId\":$CAMPAIGN_ID,\"maxCampaigns\":1}" \
    > "$EVID/consistency-start.json" || return 1
  id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["consistencyRecoveryExecutionId"])' "$EVID/consistency-start.json") || return 1
  [[ "$id" =~ ^[1-9][0-9]*$ ]] || return 1
  deadline=$((SECONDS + 120))
  while (( SECONDS < deadline )); do
    curl --max-time 10 -fsS "$base/executions/$id" > "$EVID/consistency.json" || return 1
    status=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["execution"]["status"])' "$EVID/consistency.json") || return 1
    case "$status" in
      COMPLETED) return 0 ;;
      FAILED) return 1 ;;
    esac
    sleep 3
  done
  return 1
}

P='status="202",uri=~".*participation.*"'
B="bridge_messages_published_total{campaignId=\"$CAMPAIGN_ID\"}"
step kafka-lag-final prom_final kafka-lag-final "$LAG"
step bridge-published-final prom_final bridge-published-final "sum($B)"
step bridge-counter-resets prom_final bridge-counter-resets "sum(resets($B[$(( $(json_field "$EVID/run.json" drain_end_epoch) - $(json_field "$EVID/run.json" load_start_epoch) + 30 ))s]))"
step app-continuity verify_apps
step loadgen-continuity verify_loadgen
step status-api status_api
step db-counts db_counts
step scaling scaling
step consistency consistency
step api-202-rate prom_range api_202_rps "sum(rate(http_server_requests_seconds_count{$P}[1m]))"
for percentile in 95 99; do
  step "api-p$percentile" prom_range "api_p${percentile}_ms" "1000*histogram_quantile(0.$percentile,sum by(le)(rate(http_server_requests_seconds_bucket{$P}[1m])))"
done
step bridge-rate prom_range bridge_published_rps "sum(rate($B[1m]))"
step db-rate prom_range db_committed_rps 'sum(rate(consumer_db_committed_total[1m]))'
step process-cpu prom_range process_cpu_by_instance 'max by(instance)(process_cpu_usage{job="spring-boot"})'
step hikari-pending prom_range hikari_pending_max 'max(hikaricp_connections_pending)'
step kafka-lag prom_range kafka_lag "$LAG"
echo "증거 수집 완료 $EVID"
