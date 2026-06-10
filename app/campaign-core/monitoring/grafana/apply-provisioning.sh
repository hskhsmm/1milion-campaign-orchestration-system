#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../../../.." && pwd)"

DASHBOARD_SRC="${DASHBOARD_SRC:-${REPO_ROOT}/app/campaign-core/monitoring/grafana/dashboards/campaign.json}"
MONITORING_ROOT="${MONITORING_ROOT:-/home/ssm-user/monitoring}"
GRAFANA_PROVISIONING_ROOT="${GRAFANA_PROVISIONING_ROOT:-${MONITORING_ROOT}/grafana/provisioning}"
GRAFANA_CONTAINER="${GRAFANA_CONTAINER:-grafana}"

DASHBOARD_HOST_DIR="${GRAFANA_PROVISIONING_ROOT}/dashboards/json"
PROVIDER_HOST_DIR="${GRAFANA_PROVISIONING_ROOT}/dashboards"
PROVIDER_FILE="${PROVIDER_HOST_DIR}/dashboard.yml"
DASHBOARD_TARGET="${DASHBOARD_HOST_DIR}/campaign.json"

SUDO=""
if [ "${EUID:-$(id -u)}" -ne 0 ] && command -v sudo >/dev/null 2>&1; then
  SUDO="sudo"
fi

if [ ! -f "${DASHBOARD_SRC}" ]; then
  echo "ERROR: dashboard source not found: ${DASHBOARD_SRC}" >&2
  exit 1
fi

${SUDO} mkdir -p "${DASHBOARD_HOST_DIR}"
${SUDO} cp "${DASHBOARD_SRC}" "${DASHBOARD_TARGET}"

tmp_provider="$(mktemp)"
cat > "${tmp_provider}" <<'YAML'
apiVersion: 1
providers:
  - name: campaign
    orgId: 1
    folder: ''
    type: file
    disableDeletion: false
    allowUiUpdates: true
    updateIntervalSeconds: 30
    options:
      path: /etc/grafana/provisioning/dashboards/json
YAML

${SUDO} mkdir -p "${PROVIDER_HOST_DIR}"
${SUDO} cp "${tmp_provider}" "${PROVIDER_FILE}"
rm -f "${tmp_provider}"

echo "Dashboard provisioning files were written."
echo "provider: ${PROVIDER_FILE}"
echo "dashboard: ${DASHBOARD_TARGET}"

if command -v docker >/dev/null 2>&1; then
  if ${SUDO} docker ps --format '{{.Names}}' | grep -Fxq "${GRAFANA_CONTAINER}"; then
    ${SUDO} docker restart "${GRAFANA_CONTAINER}" >/dev/null
    echo "Grafana container restarted: ${GRAFANA_CONTAINER}"
  else
    echo "WARN: Grafana container is not running: ${GRAFANA_CONTAINER}" >&2
    echo "Start or restart Grafana manually after provisioning files are written." >&2
  fi
else
  echo "WARN: docker command not found. Restart Grafana manually." >&2
fi

echo
echo "Verify inside the Grafana container:"
echo "  sudo docker exec ${GRAFANA_CONTAINER} sh -c 'grep -n \"consumer_db_committed_total\" /etc/grafana/provisioning/dashboards/json/campaign.json'"
