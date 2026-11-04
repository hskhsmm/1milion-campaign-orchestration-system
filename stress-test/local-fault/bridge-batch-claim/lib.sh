# source lib.sh — failover 시험 helper
cd "$(dirname "${BASH_SOURCE[0]}")"
: "${COMPOSE_FILE:?export COMPOSE_FILE=compose-kill.yml 또는 compose-failover.yml}"
export COMPOSE_FILE
alive_node() { for i in 1 2 3 4 5 6; do docker compose ps --status running -q redis-$i 2>/dev/null | grep -q . || continue; if docker compose exec -T redis-$i redis-cli -p 700$i PING 2>/dev/null | grep -q PONG; then echo $i; return; fi; done; }
rc() { local n=$(alive_node); docker compose exec -T redis-$n redis-cli -c -p 700$n "$@" 2>/dev/null | tr -d '\r'; }
slot_of() { rc CLUSTER KEYSLOT "queue:campaign:{$1}"; }
# 슬롯을 가진 primary 컨테이너 번호
primary_of() { local slot=$(slot_of $1); rc CLUSTER NODES | awk -v s=$slot '$3 ~ /master/ && $3 !~ /fail/ { for (i=9;i<=NF;i++) { split($i,r,"-"); if (r[2]=="") r[2]=r[1]; if (s>=r[1] && s<=r[2]) { split($2,h,","); print h[2] } } }' | sed 's/redis-//'; }
topology() { rc CLUSTER NODES | awk '{split($2,h,","); print h[2], $3, $9}' | sort; }
create_campaign() { curl -s -X POST http://127.0.0.1:18080/api/admin/campaigns -H 'Content-Type: application/json' -d "{\"name\":\"$1\",\"totalStock\":$2}" | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["id"])'; }
# 병렬 요청: send <campaign> <fromUser> <toUser> <parallel> → http 코드 분포
send() { seq $2 $3 | xargs -P $4 -I{} curl -s -o /dev/null -w '%{http_code}\n' -m 15 -X POST http://127.0.0.1:18080/api/campaigns/$1/participation -H 'Content-Type: application/json' -d '{"userId":{}}' | sort | uniq -c | awk '{printf "%s:%s ", $2, $1} END {print ""}'; }
topic_offsets() { docker compose exec -T kafka kafka-run-class kafka.tools.GetOffsetShell --bootstrap-server kafka:29092 --topic campaign-participation-topic 2>/dev/null | awk -F: '{s+=$3} END {print s+0}'; }
