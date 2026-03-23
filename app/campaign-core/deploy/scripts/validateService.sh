#!/usr/bin/env bash
set -euo pipefail

echo "[validateService] Checking application health"

RETRIES=30
SLEEP=2
URL="http://localhost:8080/actuator/health"

for i in $(seq 1 ${RETRIES}); do
  if command -v curl >/dev/null 2>&1; then
    STATUS=$(curl -fsS --max-time 2 "${URL}" | tr -d '\n' || true)
    if echo "${STATUS}" | grep -q '"status"\s*:\s*"UP"'; then
      echo "[validateService] Health OK"
      exit 0
    fi
  fi
  echo "[validateService] Waiting for health... (${i}/${RETRIES})"
  sleep ${SLEEP}
done

echo "[validateService] Health check failed"
exit 1

