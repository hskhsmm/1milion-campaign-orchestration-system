#!/usr/bin/env bash
# Read-only: show actual Job outcome separately from final participation recovery.
set -euo pipefail
base=${1:?Usage: show-dlq-replay.sh BASE_URL EXECUTION_ID}
id=${2:?Execution ID required}
[[ "$id" =~ ^[0-9]+$ ]] || { echo 'Execution ID must be numeric' >&2; exit 2; }
curl --fail --silent --show-error --max-time 20 "${base%/}/api/admin/dlq-replay/executions/$id" | python3 -m json.tool
curl --fail --silent --show-error --max-time 20 "${base%/}/api/admin/dlq-replay/executions/$id/verification" | python3 -m json.tool
