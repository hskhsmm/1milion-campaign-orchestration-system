#!/usr/bin/env bash
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/../../ops/scripts/lib-ssm.sh"

LOADGEN_REPO=/home/ssm-user/1milion-campaign-orchestration-system
LAG='sum(kafka_consumergroup_lag{consumergroup="campaign-participation-group"})'

prom_command() {
  printf "curl --max-time 10 -fsSG http://localhost:9090/api/v1/%s --data-urlencode 'query=%s'" "$1" "$2"
}

json_field() {
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[sys.argv[2]])' "$1" "$2"
}

app_snapshot() {
  local id
  for id in "$@"; do
    printf '%s ' "$id"
    ssm_run "$id" 'set -e
test "$(docker inspect -f "{{.State.Running}}" campaign-core-app)" = true
docker inspect -f '\''{{.Config.Image}} {{.State.StartedAt}}{{range .Config.Env}}{{$key := index (split . "=") 0}}{{if or (eq $key "CAMPAIGN_ROLE") (eq $key "CAMPAIGN_SCHEDULED_BATCH_ENABLED") (eq $key "BRIDGE_CLAIM_BATCH_SIZE") (eq $key "SPRING_THREADS_VIRTUAL_ENABLED")}}{{printf " %s" .}}{{end}}{{end}}'\'' campaign-core-app' || return 1
  done
}
