#!/usr/bin/env bash
# terraform-mcp에서 systemd 서비스로 실행한다. 설정 값은 상위 실행기가 전달한다.
set -euo pipefail
source ./run.env
export PATH="/usr/local/bin:$PATH"
export CAMPAIGN_ID TOTAL_REQUESTS MAX_VUS DURATION BASE_URL
export CONFIRM_BASE_URL_OVERRIDE=true LOG_EVERY=0
export K6_SUMMARY_EXPORT="$PWD/k6-summary.json"
export K6_SUMMARY_TREND_STATS='avg,med,min,max,p(95),p(99)'

watch="$RUN_ID-redis"
redis() { timeout 8 docker exec "$watch" redis-cli --raw -c -h "$REDIS_HOST" "$@"; }
load_pid=
cleanup() {
  if [[ -n "$load_pid" ]] && kill -0 "$load_pid" 2>/dev/null; then
    kill -TERM "$load_pid" 2>/dev/null || true
    wait "$load_pid" || true
  fi
  docker rm -f "$watch" >/dev/null 2>&1 || true
}
trap cleanup EXIT
trap 'echo interrupted > interruption; exit 130' INT TERM

# 단일 부하 발생기에서 동시에 두 하네스 실행이 Redis·CPU를 공유하지 않게 한다.
# 락은 k6뿐 아니라 drain이 끝날 때까지 잡는다.
exec 9>"$PWD/../.integrity.lock"
flock -n 9 || { echo competing-run > interruption; exit 1; }
docker run --rm -d --network host --name "$watch" redis:7-alpine sleep 86400 >/dev/null

sample_redis() {
  llen=$(redis LLEN "queue:campaign:{$CAMPAIGN_ID}") || return 1
  inflight=$(redis ZCARD "bridge:inflight:campaign:{$CAMPAIGN_ID}") || return 1
  stock=$(redis GET "stock:campaign:{$CAMPAIGN_ID}") || return 1
  [[ "$llen" =~ ^[0-9]+$ && "$inflight" =~ ^[0-9]+$ && "$stock" =~ ^[0-9]*$ ]] || return 1
  printf '%s inSet= flag= llen=%s inflight=%s stock=%s\n' "$(TZ=Asia/Seoul date '+%F %T')" "$llen" "$inflight" "$stock" >> redis-watch.log
}

# 모든 primary의 실제 used_memory를 읽는다. 한 노드라도 읽을 수 없으면 경계를 확인할 수 없다.
sample_memory() {
  local topology node bytes
  local -a primaries=()
  topology=$(redis CLUSTER NODES) || return 1
  while read -r node; do
    primaries+=("$node")
  done < <(awk '$3 ~ /(^|,)master(,|$)/ && $3 !~ /fail|noaddr|handshake/ && $8 == "connected" { sub(/@.*/, "", $2); print $2 }' <<< "$topology")
  (( ${#primaries[@]} == REDIS_PRIMARIES )) || return 1
  for node in "${primaries[@]}"; do
    [[ "$node" =~ ^[a-zA-Z0-9.-]+:[0-9]+$ ]] || return 1
    bytes=$(timeout 8 docker exec "$watch" redis-cli --raw -h "${node%:*}" -p "${node##*:}" INFO memory | awk -F: '$1=="used_memory" {gsub(/\r/, "", $2); print $2}') || return 1
    [[ "$bytes" =~ ^[0-9]+$ ]] || return 1
    printf '%s %s %s\n' "$(date +%s)" "$node" "$bytes" >> redis-memory.tsv
    (( bytes < REDIS_GUARD_BYTES )) || return 2
  done
}

sample_redis
[[ "$llen" == 0 && "$inflight" == 0 && "$stock" == "$TOTAL_REQUESTS" ]] || { echo unexpected-initial-redis-state > interruption; exit 1; }
sample_memory || { echo redis-memory-preflight > interruption; exit 1; }

date +%s > load-start
# exec을 사용해 PID가 k6 자체를 가리키게 한다. 옵션은 기존 integrity 스크립트와 같다.
(
  exec k6 run -e BASE_URL="$BASE_URL" -e CAMPAIGN_ID="$CAMPAIGN_ID" \
    -e TOTAL_REQUESTS="$TOTAL_REQUESTS" -e MAX_VUS="$MAX_VUS" -e DURATION="$DURATION" \
    -e LOG_EVERY=0 "$LOADGEN_REPO/stress-test/k6-load-test.js"
) > k6-run.log 2>&1 &
load_pid=$!

while kill -0 "$load_pid" 2>/dev/null; do
  if ! sample_redis; then
    echo redis-sample-unavailable > interruption
  elif sample_memory; then
    :
  else
    case "$?" in
      2) echo redis-memory-guard > interruption ;;
      *) echo redis-memory-unavailable > interruption ;;
    esac
  fi
  if [[ -s interruption ]]; then
    kill -TERM "$load_pid" 2>/dev/null || true
    break
  fi
  sleep 5
done
status=0
wait "$load_pid" || status=$?
load_pid=
date +%s > load-end
printf '%s\n' "$status" > k6-exit

# 두 번 연속 Queue·in-flight·lag 0을 확인한다. 변화가 Prometheus scrape에 나타날 시간을 준다.
drain_start=$SECONDS
zeros=0
while (( SECONDS - drain_start < DRAIN_TIMEOUT )); do
  if sample_redis && curl --max-time 10 -fsSG http://localhost:9090/api/v1/query \
    --data-urlencode "query=$LAG" > kafka-lag-final.json &&
    [[ "$llen" == 0 && "$inflight" == 0 ]] &&
    python3 -c 'import json; d=json.load(open("kafka-lag-final.json")); r=d["data"]["result"]; assert d["status"]=="success" and len(r)==1 and float(r[0]["value"][1])==0' 2>/dev/null; then
    zeros=$((zeros + 1))
    if (( zeros == 2 )); then
      printf '{"drained":true,"waited_seconds":%d}\n' "$(( SECONDS - drain_start ))" > drain.json
      break
    fi
  else
    zeros=0
  fi
  sleep 20
done
if [[ ! -s drain.json ]]; then
  # 마지막 상태가 불명확하면 timeout과 잔량을 혼동하지 않는다.
  printf '{"drained":null,"waited_seconds":%d}\n' "$(( SECONDS - drain_start ))" > drain.json
fi
date +%s > drain-end
