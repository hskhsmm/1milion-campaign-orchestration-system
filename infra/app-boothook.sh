#!/bin/bash
# cloud-init boothook: cloud-init init 단계(docker.service 기동 전)에서 실행된다.
#
# 앱 AMI(ami-01c64e7a84a57e681)에는 restart=unless-stopped인 구버전 앱 컨테이너가 구워져 있어,
# 새 인스턴스가 부팅하면 dockerd가 이 컨테이너를 자동 기동하고 CodeDeploy가 교체하기 전까지
# prod Redis/DB에 붙어 active:campaigns를 조작한다 (docs/current/2026-09-28-150m-reproduction-set-removal-trace.md).
# dockerd가 뜨기 전에 남아 있는 컨테이너의 restart 정책을 "no"로 바꿔 자동 기동을 막는다.
# 남은 컨테이너 삭제는 docker가 뜬 뒤 app-user-data.sh에서 한다.
#
# boothook은 매 부팅마다 실행되므로, 배포된 앱 컨테이너가 재부팅 후에도 살아나도록 인스턴스당 1회만 실행한다.

LOG_FILE="/var/log/batch-kafka-app-boothook.log"
MARKER_DIR="/var/lib/batch-kafka-app"
exec >>"${LOG_FILE}" 2>&1

# cloud-init이 boothook에 INSTANCE_ID를 넘긴다. 없으면 cloud-init 데이터에서 읽는다.
# 마커가 AMI에 구워져도 인스턴스 ID가 다르므로 새 인스턴스에서는 반드시 다시 실행된다.
instance_id="${INSTANCE_ID:-$(cat /var/lib/cloud/data/instance-id 2>/dev/null)}"
if [[ -z "${instance_id}" ]]; then
  instance_id="unknown-$(date +%s)"
fi
MARKER="${MARKER_DIR}/boothook-sanitized-${instance_id}"

echo "[app-boothook] $(date -u +%FT%TZ) start instance=${instance_id}"

if [[ -f "${MARKER}" ]]; then
  echo "[app-boothook] already sanitized on this instance, skip"
  exit 0
fi

if systemctl is-active --quiet docker; then
  # 예상과 달리 docker가 이미 떠 있으면 컨테이너를 바로 제거한다.
  echo "[app-boothook] WARN: docker already active, removing containers directly"
  docker ps -a --format '{{.ID}} {{.Names}} {{.Image}} {{.Status}}' || true
  docker ps -aq | xargs -r docker rm -f || true
else
  for hostconfig in /var/lib/docker/containers/*/hostconfig.json; do
    [[ -f "${hostconfig}" ]] || continue
    echo "[app-boothook] disable restart policy: ${hostconfig}"
    sed -i 's/"RestartPolicy":{"Name":"[^"]*"/"RestartPolicy":{"Name":"no"/' "${hostconfig}"
  done
fi

mkdir -p "${MARKER_DIR}"
touch "${MARKER}"
echo "[app-boothook] done"
