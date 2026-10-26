#!/usr/bin/env bash
# 외부 AWS/Redis 없이 Cluster master 전체 검사와 실패 차단을 확인한다.
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../.." && pwd)
guard="$repo_root/ops/scripts/check-bridge-rollback-state.sh"
counter_file=$(mktemp)
trap 'rm -f "$counter_file"' EXIT

function redis-cli() {
  case " $* " in
    *' CLUSTER NODES '*)
      [ "$MOCK_MODE" != redis_error ] || return 1
      host=config
      case " $* " in
        *' -h master-1 '*) host=master-1 ;;
        *' -h master-2 '*) host=master-2 ;;
      esac
      first_slots=0-8191
      second_slots=8192-16383
      if [ "$host" = master-1 ]; then
        calls=$(cat "$counter_file")
        calls=$((calls + 1))
        printf '%s\n' "$calls" > "$counter_file"
        if [ "$MOCK_MODE" = slot_change ] && [ "$calls" -ge 2 ]; then
          first_slots=0-4095
        fi
        if [ "$MOCK_MODE" = migrating ]; then
          first_slots="$first_slots [4096->-two]"
        fi
      elif [ "$host" = master-2 ] && [ "$MOCK_MODE" = slot_change ]; then
        calls=$(cat "$counter_file")
        if [ "$calls" -ge 2 ]; then
          second_slots=4096-16383
        fi
      fi
      first_flags=master
      second_flags=master
      if [ "$host" = master-1 ]; then first_flags=myself,master; fi
      if [ "$host" = master-2 ]; then second_flags=myself,master; fi
      printf '%s\n' \
        "one master-1:6379@16379 $first_flags - 0 0 1 connected $first_slots" \
        "two master-2:6379@16379 $second_flags - 0 0 2 connected $second_slots"
      ;;
    *' SCARD active:campaigns '*)
      if [ "$MOCK_MODE" = active ]; then echo 1; else echo 0; fi
      ;;
    *' --scan --pattern queue:campaign:* '*)
      case " $* " in
        *' -h master-1 '*) echo 'queue:campaign:{91}' ;;
      esac
      ;;
    *' --scan --pattern bridge:inflight:campaign:* '*)
      case " $* " in
        *' -h master-2 '*) echo 'bridge:inflight:campaign:{97}' ;;
      esac
      ;;
    *' LLEN queue:campaign:{91} '*)
      if [ "$MOCK_MODE" = queue ]; then echo 2; else echo 0; fi
      ;;
    *' ZCARD bridge:inflight:campaign:{97} '*)
      if [ "$MOCK_MODE" = inflight ]; then echo 5; else echo 0; fi
      ;;
    *) echo "Unexpected redis-cli call: $*" >&2; return 1 ;;
  esac
}

check_case() {
  mode=$1
  expected=$2
  printf '0\n' > "$counter_file"
  if output=$(MOCK_MODE=$mode; . "$guard" config 6379 2>&1); then
    actual=pass
  else
    actual=blocked
  fi
  [ "$actual" = "$expected" ] || {
    echo "FAIL: mode=$mode expected=$expected actual=$actual output=$output" >&2
    exit 1
  }
  if [ "$mode" = migrating ]; then
    [[ "$output" == *'migrating slots'* ]] || {
      echo "FAIL: slot migration was blocked for the wrong reason: $output" >&2
      exit 1
    }
  elif [ "$mode" = slot_change ]; then
    [[ "$output" == *'slots changed during inspection'* ]] || {
      echo "FAIL: slot change was blocked for the wrong reason: $output" >&2
      exit 1
    }
  fi
}

check_case clean pass
check_case inflight blocked
check_case queue blocked
check_case active blocked
check_case redis_error blocked
check_case migrating blocked
check_case slot_change blocked
echo 'Bridge rollback state guard tests passed'
