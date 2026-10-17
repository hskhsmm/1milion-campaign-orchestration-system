#!/usr/bin/env bash
# 부하가 끝난 뒤 증거를 모은다. 읽기 전용이다(DB는 SELECT만 실행).
# 단계가 실패해도 멈추지 않고 collect-errors.txt에 남긴다. 판정기는 이 파일이 있으면 INCONCLUSIVE를 낸다.
#
# 사용법 (운영 PC, 저장소 루트): bash stress-test/harness/collect.sh <증거 디렉터리>
#   <증거 디렉터리>/run.json의 run_id, campaign_id, load_start_epoch, load_end_epoch를 사용한다.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/../../ops/scripts/lib-ssm.sh"

EVID="${1:?사용법: $0 <증거 디렉터리>}"
run_field() { python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[sys.argv[2]])' "$EVID/run.json" "$1"; }
RUN_ID=$(run_field run_id)
C=$(run_field campaign_id)
START=$(( $(run_field load_start_epoch) - 30 ))
# 조회 끝: drain 종료 2분 뒤 (나중에 다시 수집해도 같은 구간을 조회한다)
END=$(python3 -c 'import json,sys,time; d=json.load(open(sys.argv[1])); e=d.get("drain_end_epoch", d.get("load_end_epoch")); print(min(int(time.time()), int(e)+120) if e else int(time.time()))' "$EVID/run.json")
MCP=$(mcp_instance_id)
R=/home/ssm-user/1milion-campaign-orchestration-system/stress-test/evidence/$RUN_ID
mkdir -p "$EVID/prometheus"
: > "$EVID/collect-errors.txt"

step() { # step <이름> <명령...>
  local name="$1"; shift
  if ! "$@"; then echo "$name: 실패" >> "$EVID/collect-errors.txt"; echo "수집 실패: $name" >&2; fi
}

# 1. Redis 감시 기록 (감시 컨테이너 종료)
step redis-watch bash "$HERE/../../ops/scripts/redis-watch-fetch.sh" --stop "$EVID"

# 2. k6 결과
step k6-summary ssm_fetch "$MCP" "cat $R/k6-summary.json" "$EVID/k6-summary.json"
step k6-run-log ssm_fetch "$MCP" "cat $R/k6-run.log" "$EVID/k6-run.log"

# 3. Prometheus (10초 step, 1분 rate). 쿼리는 prometheus/queries.tsv에 남긴다.
STEP=10
echo "# start=$START end=$END step=${STEP}s" > "$EVID/prometheus/queries.tsv"
prom_range() {
  printf '%s\t%s\n' "$1" "$2" >> "$EVID/prometheus/queries.tsv"
  # SSM 출력은 24,000자에서 잘리므로 ssm_fetch로 받고, 받은 JSON이 온전한지 확인한다.
  ssm_fetch "$MCP" "curl -fsSG localhost:9090/api/v1/query_range --data-urlencode 'query=$2' --data-urlencode 'start=$START' --data-urlencode 'end=$END' --data-urlencode 'step=$STEP'" "$EVID/prometheus/$1.json" 2>/dev/null &&
    python3 -c 'import json,sys; assert json.load(open(sys.argv[1]))["status"] == "success"' "$EVID/prometheus/$1.json"
}
P='uri="/api/campaigns/{campaignId}/participation"'
LAG='sum(kafka_consumergroup_lag{consumergroup="campaign-participation-group"})'
step prom-api prom_range api_202_rps "sum(rate(http_server_requests_seconds_count{$P,status=\"202\"}[1m]))"
step prom-p95 prom_range api_p95_ms "1000*histogram_quantile(0.95, sum by (le)(rate(http_server_requests_seconds_bucket{$P}[1m])))"
step prom-p99 prom_range api_p99_ms "1000*histogram_quantile(0.99, sum by (le)(rate(http_server_requests_seconds_bucket{$P}[1m])))"
step prom-bridge prom_range bridge_published_rps 'sum(rate(bridge_messages_published_total[1m]))'
step prom-db prom_range db_committed_rps 'sum(rate(consumer_db_committed_total[1m]))'
step prom-queue prom_range queue_gauge "sum(redis_queue_size{campaignId=\"$C\"})"
step prom-cpu prom_range cpu_by_instance 'max by (instance)(system_cpu_usage{job="spring-boot"})'
step prom-hikari prom_range hikari_pending_max 'max(hikaricp_connections_pending)'
step prom-lag prom_range kafka_lag "$LAG"
cp "$EVID/prometheus/kafka_lag.json" "$EVID/kafka-lag-range.json" 2>/dev/null
lag_final() {
  ssm_run "$MCP" "curl -fsSG localhost:9090/api/v1/query --data-urlencode 'query=$LAG'" > "$EVID/kafka-lag-final.json" &&
    python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$EVID/kafka-lag-final.json"
}
step kafka-lag-final lag_final

# 4. 상태 API
status_api() { curl -fsS "$(bash "$HERE/../resolve-base-url.sh")/api/campaigns/$C/status" > "$EVID/status.json"; }
step status-api status_api

# 5. DB 직접 조회 (SELECT만)
db_counts() {
  local inst sql remote
  inst=$(app_instance_ids | awk '{print $1}')
  sql="SELECT NOW(6) AS checked_at; SELECT COUNT(*) AS total_rows, COALESCE(SUM(status='SUCCESS'),0) AS success_count FROM participation_history WHERE campaign_id=$C; SELECT COUNT(*) AS dup_sequence_groups FROM (SELECT sequence FROM participation_history WHERE campaign_id=$C GROUP BY sequence HAVING COUNT(*)>1) t; SELECT COUNT(*) AS dup_user_groups FROM (SELECT user_id FROM participation_history WHERE campaign_id=$C GROUP BY user_id HAVING COUNT(*)>1) t;"
  # ops/scripts/close-stale-campaigns.sh와 같은 방식으로 .env.prod 값만 읽는다.
  remote=$(cat <<EOF
set -euo pipefail
env_get() { grep -m1 "^\$1=" /opt/campaign-core/.env.prod | cut -d= -f2-; }
url="\$(env_get SPRING_DATASOURCE_URL)"; url="\${url#jdbc:mysql://}"
hostport="\${url%%/*}"; db="\${url#*/}"; db="\${db%%\?*}"
host="\${hostport%%:*}"; port="\${hostport##*:}"; [ "\$port" = "\$host" ] && port=3306
docker run --rm -e MYSQL_PWD="\$(env_get SPRING_DATASOURCE_PASSWORD)" mysql:8.4 \
  mysql -h "\$host" -P "\$port" -u "\$(env_get SPRING_DATASOURCE_USERNAME)" "\$db" --table -e "$sql"
EOF
)
  { echo "instance=$inst"; echo "sql=$sql"; ssm_run "$inst" "$remote"; } > "$EVID/db-counts.txt"
}
step db-counts db_counts

# 5-1. 정합성 dry-run (TEST_GUIDE 8.2). dryRun=true·autoFix=false라 데이터는 바꾸지 않고 실행 기록만 남는다.
consistency() {
  local base id status
  base="$(bash "$HERE/../resolve-base-url.sh")/api/admin/consistency-recovery"
  id=$(curl -fsS -X POST "$base" -H 'Content-Type: application/json' \
    -d "{\"requestedBy\":\"harness-$RUN_ID\",\"dryRun\":true,\"autoFix\":false,\"campaignId\":$C,\"maxCampaigns\":1}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["consistencyRecoveryExecutionId"])') || return 1
  for _ in $(seq 1 60); do
    curl -fsS "$base/executions/$id" > "$EVID/consistency.json" || return 1
    status=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["execution"]["status"])' "$EVID/consistency.json")
    [[ "$status" == COMPLETED ]] && return 0
    [[ "$status" == FAILED ]] && return 1
    sleep 2
  done
  return 1
}
step consistency consistency

# 6. ASG scaling activity (실행 중 변경 확인)
scaling() {
  aws_ autoscaling describe-scaling-activities --auto-scaling-group-name batch-kafka-app-asg --max-items 20 \
    --query 'Activities[].{StartTime:StartTime,Description:Description}' --output json > "$EVID/scaling.json"
}
step scaling scaling

# 7. 앱 로그: 전체 로그는 SSM 전송이 느려서 테스트 구간의 ERROR/WARN/Exception만 가져온다.
app_logs() {
  local i
  for i in $(app_instance_ids); do
    ssm_run "$i" "docker inspect -f '{{.Config.Image}} {{.State.StartedAt}}' campaign-core-app" > "$EVID/app-$i.meta" || return 1
    ssm_fetch "$i" "docker logs --since $START --until $END campaign-core-app 2>&1 | { grep -E ' (ERROR|WARN) |Exception' || true; } | cut -c1-400 | tail -500" "$EVID/app-$i.errwarn.log" 2>/dev/null || return 1
  done
}
step app-logs app_logs

[[ -s "$EVID/collect-errors.txt" ]] || rm -f "$EVID/collect-errors.txt"
echo "수집 완료: $EVID"
