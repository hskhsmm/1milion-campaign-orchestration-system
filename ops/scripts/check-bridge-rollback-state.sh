#!/bin/sh
# Redis Cluster의 모든 master에서 Queue/in-flight 키를 확인한다.
# 롤백 전에는 신규 캠페인/참여 요청을 멈춘 상태여야 한다. 이 검사는 쓰기를 막지 못한다.
set -eu

redis_host=${1:?usage: check-bridge-rollback-state.sh <redis-host> [port]}
redis_port=${2:-6379}

case "$redis_port" in
  *[!0-9]*|'') echo "Invalid Redis port: $redis_port" >&2; exit 2 ;;
esac

fail() {
  echo "ROLLBACK BLOCKED: $*" >&2
  exit 1
}

number() {
  case "$1" in
    *[!0-9]*|'') return 1 ;;
    *) return 0 ;;
  esac
}

command -v redis-cli >/dev/null 2>&1 || fail "redis-cli is unavailable"

nodes=$(redis-cli --raw -h "$redis_host" -p "$redis_port" CLUSTER NODES) \
  || fail "cannot read Redis Cluster nodes"
masters=$(printf '%s\n' "$nodes" | awk '$3 ~ /(^|,)master(,|$)/ { split($2, address, "@"); print address[1] }' | sort -u)
[ -n "$masters" ] || fail "no Redis Cluster masters found"

# 각 master가 보고하는 자신의 ID·주소·슬롯 범위를 기록한다. 슬롯 이동 표시는
# CLUSTER NODES의 myself 행에만 있으므로 설정 호스트의 응답만으로는 부족하다.
master_layout() {
  for address in $masters; do
    node_host=${address%:*}
    node_port=${address##*:}
    [ "$node_host" != "$address" ] && number "$node_port" \
      || fail "invalid Redis Cluster master address: $address"

    local_nodes=$(redis-cli --raw -h "$node_host" -p "$node_port" CLUSTER NODES) \
      || fail "cannot read Redis Cluster nodes from $address"
    own_layout=$(printf '%s\n' "$local_nodes" | awk -v expected="$address" '
      $3 ~ /(^|,)myself(,|$)/ {
        count++
        split($2, advertised, "@")
        if ($3 !~ /(^|,)master(,|$)/ || advertised[1] != expected || NF < 9) exit 1
        for (i = 9; i <= NF; i++) {
          if ($i ~ /^\[/) exit 1
          slots = slots " " $i
        }
        print $1, advertised[1], slots
      }
      END { if (count != 1) exit 1 }
    ') || fail "Redis Cluster master is migrating slots or has invalid layout: $address"
    [ -n "$own_layout" ] || fail "missing Redis Cluster master layout: $address"
    printf '%s\n' "$own_layout"
  done
}

layout=$(master_layout) || fail "cannot snapshot Redis Cluster master layout"

active=$(redis-cli -c --raw -h "$redis_host" -p "$redis_port" SCARD active:campaigns) \
  || fail "cannot read active campaign set"
number "$active" || fail "invalid active campaign count: $active"

queue_total=0
inflight_total=0
for address in $masters; do
  node_host=${address%:*}
  node_port=${address##*:}
  [ "$node_host" != "$address" ] && number "$node_port" \
    || fail "invalid Redis Cluster master address: $address"

  for pattern in 'queue:campaign:*' 'bridge:inflight:campaign:*'; do
    keys=$(redis-cli --raw -h "$node_host" -p "$node_port" --scan --pattern "$pattern") \
      || fail "SCAN failed on $address for $pattern"
    [ -n "$keys" ] || continue

    # SCAN 결과의 각 줄을 읽되 루프를 서브셸로 만들지 않아 합계가 유지되게 한다.
    while IFS= read -r key; do
      [ -n "$key" ] || continue
      if [ "$pattern" = 'queue:campaign:*' ]; then
        count=$(redis-cli -c --raw -h "$node_host" -p "$node_port" LLEN "$key") \
          || fail "LLEN failed for $key"
        number "$count" || fail "invalid Queue count for $key: $count"
        queue_total=$((queue_total + count))
      else
        count=$(redis-cli -c --raw -h "$node_host" -p "$node_port" ZCARD "$key") \
          || fail "ZCARD failed for $key"
        number "$count" || fail "invalid in-flight count for $key: $count"
        inflight_total=$((inflight_total + count))
      fi
    done <<EOF
$keys
EOF
  done
done

# 검사 도중 failover나 슬롯 이동이 발생했다면 일부 키를 빠뜨렸을 수 있으므로 실패한다.
nodes_after=$(redis-cli --raw -h "$redis_host" -p "$redis_port" CLUSTER NODES) \
  || fail "cannot recheck Redis Cluster nodes"
masters_after=$(printf '%s\n' "$nodes_after" | awk '$3 ~ /(^|,)master(,|$)/ { split($2, address, "@"); print address[1] }' | sort -u)
[ "$masters" = "$masters_after" ] || fail "Redis Cluster masters changed during inspection"
layout_after=$(master_layout) || fail "cannot recheck Redis Cluster master layout"
[ "$layout" = "$layout_after" ] || fail "Redis Cluster slots changed during inspection"

# 검사 도중 새 캠페인이 활성화됐으면 실패한다. 그래도 TOCTOU를 없애지는 못한다.
active_after=$(redis-cli -c --raw -h "$redis_host" -p "$redis_port" SCARD active:campaigns) \
  || fail "cannot recheck active campaign set"
number "$active_after" || fail "invalid active campaign count: $active_after"

echo "Bridge rollback preflight: active=$active_after queue=$queue_total inflight=$inflight_total"
[ "$active" -eq 0 ] && [ "$active_after" -eq 0 ] && [ "$queue_total" -eq 0 ] \
  && [ "$inflight_total" -eq 0 ] \
  || fail "active campaigns, Queue, or in-flight work remain"

echo 'Bridge rollback preflight: PASS (only while new writes remain stopped)'
