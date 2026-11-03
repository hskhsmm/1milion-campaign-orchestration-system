#!/usr/bin/env bash
# 사용: obs.sh <campaignId> [label]  — Redis·DB·Kafka·앱 지표 한 줄 요약
set -u
cd "$(dirname "$0")"
id=$1; label=${2:-}
source ./lib.sh
r() { rc "$@"; }
q() { docker compose exec -T mysql mysql -ubbc -pbbc-test campaign -N -e "$1" 2>/dev/null; }
llen=$(r LLEN "queue:campaign:{$id}")
zcard=$(r ZCARD "bridge:inflight:campaign:{$id}")
db=$(q "select count(*), count(distinct user_id), count(sequence), count(distinct sequence), sum(status='SUCCESS') from participation_history where campaign_id=$id")
dlq=$(q "select count(*) from dlq_message where campaign_id=$id" || echo "?")
lag=$(docker compose exec -T kafka kafka-consumer-groups --bootstrap-server kafka:29092 --describe --group campaign-participation-group 2>/dev/null | awk 'NR>1 && $6 ~ /^[0-9]+$/ {s+=$6} END {print s+0}')
m=""
if curl -sf -m 2 http://127.0.0.1:18080/actuator/health >/dev/null 2>&1; then
  m=$(curl -s -m 5 http://127.0.0.1:18080/actuator/prometheus | awk '
    /^bridge_claim_events_total/ {ev=$NF}
    /^bridge_claim_calls_total.*result="claimed"/ {cc=$NF}
    /^bridge_claim_ack_results_total.*result="removed"/ {ar=$NF}
    /^bridge_claim_ack_results_total.*result="not_found"/ {an=$NF}
    /^bridge_claim_ack_results_total.*result="error"/ {ae=$NF}
    /^bridge_claim_ack_results_total.*result="rejected"/ {aj=$NF}
    /^bridge_messages_published_total/ {pub+=$NF}
    /^bridge_claims_reclaimed_total/ {rc+=$NF}
    END {printf "app: claim_calls=%s claim_events=%s published=%s ack[removed=%s not_found=%s error=%s rejected=%s] reclaimed=%s", cc, ev, pub+0, ar, an, ae, aj, rc+0}')
else
  m="app: DOWN"
fi
echo "$(date '+%H:%M:%S') [$label] campaign=$id queue=$llen inflight=$zcard db(rows,distinct_user,seq,distinct_seq,success)=[$db] dlq=$dlq lag=$lag | $m"
