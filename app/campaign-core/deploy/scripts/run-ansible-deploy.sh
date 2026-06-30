#!/usr/bin/env bash
set -euo pipefail

HOOK_TAG="${1:-}"

if [[ -z "${HOOK_TAG}" ]]; then
  case "${LIFECYCLE_EVENT:-}" in
    BeforeInstall)
      HOOK_TAG="before_install"
      ;;
    AfterInstall)
      HOOK_TAG="after_install"
      ;;
    ApplicationStart)
      HOOK_TAG="application_start"
      ;;
    ValidateService)
      HOOK_TAG="validate_service"
      ;;
  esac
fi

if [[ -z "${HOOK_TAG}" ]]; then
  echo "[run-ansible-deploy] ERROR: lifecycle tag argument or LIFECYCLE_EVENT is required" >&2
  exit 1
fi

install_ansible() {
  echo "[run-ansible-deploy] ansible-playbook is not installed. Trying bootstrap install."

  if command -v dnf >/dev/null 2>&1; then
    dnf install -y ansible || dnf install -y ansible-core
  elif command -v yum >/dev/null 2>&1; then
    yum install -y ansible \
      || yum install -y ansible-core \
      || amazon-linux-extras install -y ansible2 \
      || python3 -m pip install ansible
  elif command -v python3 >/dev/null 2>&1; then
    python3 -m pip install ansible
  else
    echo "[run-ansible-deploy] ERROR: no supported package manager or python3 found for Ansible install" >&2
    return 1
  fi
}

if ! command -v ansible-playbook >/dev/null 2>&1; then
  install_ansible
fi

if ! command -v ansible-playbook >/dev/null 2>&1; then
  echo "[run-ansible-deploy] ERROR: ansible-playbook is still unavailable after bootstrap install" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUNDLE_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
OPS_DIR="${BUNDLE_ROOT}/ops"
PLAYBOOK="${OPS_DIR}/playbooks/deploy-app.yml"
INVENTORY="${OPS_DIR}/inventory/localhost.yml"

if [[ ! -f "${PLAYBOOK}" ]]; then
  echo "[run-ansible-deploy] ERROR: playbook not found: ${PLAYBOOK}" >&2
  exit 1
fi

if [[ ! -f "${INVENTORY}" ]]; then
  echo "[run-ansible-deploy] ERROR: inventory not found: ${INVENTORY}" >&2
  exit 1
fi

echo "[run-ansible-deploy] bundle_root=${BUNDLE_ROOT}"
echo "[run-ansible-deploy] hook_tag=${HOOK_TAG}"

cd "${OPS_DIR}"
ANSIBLE_CONFIG="${OPS_DIR}/ansible.cfg" \
  ansible-playbook \
    -i "${INVENTORY}" \
    "${PLAYBOOK}" \
    --tags "${HOOK_TAG}"
