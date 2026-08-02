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

# dnf/yum의 ansible(-core) 패키지는 그 순간 저장소에 있는 버전이 그대로 깔려
# 실행 시점마다 버전이 달라질 수 있다. 그래서 패키지매니저는 python3/pip
# 준비에만 쓰고, Ansible 자체는 항상 pip로 이 버전을 고정 설치한다.
# infra/app-user-data.sh의 ANSIBLE_PIP_VERSION과 동일하게 유지한다.
ANSIBLE_PIP_VERSION="9.5.1"

install_ansible() {
  echo "[run-ansible-deploy] ansible-playbook is not installed. Trying bootstrap install."

  if command -v dnf >/dev/null 2>&1; then
    dnf install -y python3 python3-pip
  elif command -v yum >/dev/null 2>&1; then
    yum install -y python3 python3-pip
  elif ! command -v python3 >/dev/null 2>&1; then
    echo "[run-ansible-deploy] ERROR: no supported package manager or python3 found for Ansible install" >&2
    return 1
  fi

  python3 -m pip install "ansible==${ANSIBLE_PIP_VERSION}"
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
