#!/usr/bin/env bash
# 수동으로 구버전 앱을 재배포하기 전, terraform-mcp에서 Redis Cluster 전체를 읽기 전용 점검한다.
# PASS 후에도 새 참여가 들어오면 결과가 무효가 되므로 캠페인/요청을 먼저 중지해야 한다.
set -euo pipefail
source "$(dirname "$0")/lib-ssm.sh"

script_dir="$(cd "$(dirname "$0")" && pwd)"
guard_script="$script_dir/check-bridge-rollback-state.sh"

[[ -f "$guard_script" ]] || { echo "Guard script not found: $guard_script" >&2; exit 1; }
command -v aws >/dev/null || { echo 'aws CLI is required' >&2; exit 1; }

mcp_id=$(mcp_instance_id)
host=$(redis_host)
[[ "$host" =~ ^[a-zA-Z0-9.-]+$ && "$host" != pending ]] \
  || { echo "Invalid Redis host: $host" >&2; exit 1; }

# SSM의 원격 셸에 코드를 인라인으로 전달한다. 파일은 EC2에 남기지 않는다.
encoded=$(base64 < "$guard_script" | tr -d '\n')
remote="set -euo pipefail; printf '%s' '$encoded' | base64 -d | docker run --rm -i --network host redis:7-alpine sh -s -- '$host' 6379"

echo "Checking Bridge rollback safety on terraform-mcp ($mcp_id), Redis Cluster $host"
ssm_run "$mcp_id" "$remote"
