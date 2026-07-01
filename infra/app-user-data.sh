#!/bin/bash
set -euxo pipefail

LOG_FILE="/var/log/batch-kafka-app-user-data.log"
exec > >(tee -a "${LOG_FILE}" | logger -t batch-kafka-app-user-data -s 2>/dev/console) 2>&1

echo "[app-user-data] start"

install_ansible_with_dnf() {
  dnf install -y ansible \
    || dnf install -y ansible-core \
    || {
      dnf install -y python3 python3-pip
      python3 -m pip install ansible
    }
}

install_ansible_with_yum() {
  yum install -y ansible \
    || yum install -y ansible-core \
    || amazon-linux-extras install -y ansible2 \
    || {
      yum install -y python3 python3-pip
      python3 -m pip install ansible
    }
}

install_ansible_with_python() {
  python3 -m ensurepip --upgrade || true
  python3 -m pip install ansible
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
