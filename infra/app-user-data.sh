#!/bin/bash
set -euxo pipefail

LOG_FILE="/var/log/batch-kafka-app-user-data.log"
exec > >(tee -a "${LOG_FILE}" | logger -t batch-kafka-app-user-data -s 2>/dev/console) 2>&1

echo "[app-user-data] start"

# dnf/yum의 ansible(-core) 패키지는 그 순간 저장소에 있는 버전이 그대로 깔려
# 실행 시점마다 버전이 달라질 수 있다. 그래서 패키지매니저는 python3/pip
# 준비에만 쓰고, Ansible 자체는 항상 pip로 이 버전을 고정 설치한다.
# run-ansible-deploy.sh의 ANSIBLE_PIP_VERSION과 동일하게 유지한다.
ANSIBLE_PIP_VERSION="9.5.1"

install_ansible_with_dnf() {
  dnf install -y python3 python3-pip
  python3 -m pip install "ansible==${ANSIBLE_PIP_VERSION}"
}

install_ansible_with_yum() {
  yum install -y python3 python3-pip
  python3 -m pip install "ansible==${ANSIBLE_PIP_VERSION}"
}

install_ansible_with_python() {
  python3 -m ensurepip --upgrade || true
  python3 -m pip install "ansible==${ANSIBLE_PIP_VERSION}"
}

# App instances are created by ASG, so deployment runtime dependencies must be
# prepared at launch time instead of being installed manually on each EC2.
if command -v ansible-playbook >/dev/null 2>&1; then
  echo "[app-user-data] ansible-playbook already installed: $(ansible-playbook --version | head -n 1)"
elif command -v dnf >/dev/null 2>&1; then
  install_ansible_with_dnf
elif command -v yum >/dev/null 2>&1; then
  install_ansible_with_yum
elif command -v python3 >/dev/null 2>&1; then
  install_ansible_with_python
else
  echo "[app-user-data] ERROR: no supported package manager or python3 found for Ansible install" >&2
  exit 1
fi

ansible-playbook --version | head -n 1
echo "[app-user-data] completed"
