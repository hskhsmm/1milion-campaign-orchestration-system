#!/usr/bin/env bash
# 외부 AWS/Redis 없이 Cluster master 전체 검사와 실패 차단을 확인한다.
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../.." && pwd)
guard="$repo_root/ops/scripts/check-bridge-rollback-state.sh"

function redis-cli() {
  case " $* " in
    *' CLUSTER NODES '*)
      [ "$MOCK_MODE" != redis_error ] || return 1
      printf '%s\n' \
        'one master-1:6379@16379 master - 0 0 1 connected 0-8191' \
        'two master-2:6379@16379 master - 0 0 2 connected 8192-16383'
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
  if output=$(MOCK_MODE=$mode; . "$guard" config 6379 2>&1); then
    actual=pass
  else
    actual=blocked
  fi
  [ "$actual" = "$expected" ] || {
    echo "FAIL: mode=$mode expected=$expected actual=$actual output=$output" >&2
    exit 1
  }
}

check_case clean pass
check_case inflight blocked
check_case queue blocked
check_case active blocked
check_case redis_error blocked
echo 'Bridge rollback state guard tests passed'
