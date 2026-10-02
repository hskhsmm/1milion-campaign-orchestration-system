#!/usr/bin/env bash
# 새로 뜨는 앱 인스턴스에서 배포 전에 자동 기동되는 컨테이너(AMI에 남은 구버전)의
# 이미지 태그와 로그를, 배포 스크립트가 지우기 전에 인스턴스 /tmp/boot-capture/ 에 복사해 둔다.
#
# 사용법: ./ops/scripts/capture-boot-containers.sh <instance-id>
#   인스턴스가 Pending 상태일 때 실행한다. SSM Agent가 Online이 될 때까지 기다린 뒤
#   6분 동안 2초마다 실행 중인 컨테이너 전부의 로그와 메타데이터를 저장한다.
# 결과 회수: collect-app-logs.sh와 같은 방식으로 ssm_fetch 사용 (fetch-boot-capture 참고)
set -euo pipefail
source "$(dirname "$0")/lib-ssm.sh"

INSTANCE_ID="${1:?사용법: $0 <instance-id>}"

echo "SSM Online 대기: $INSTANCE_ID"
for _ in $(seq 1 90); do
  ping=$(aws_ ssm describe-instance-information --filters "Key=InstanceIds,Values=$INSTANCE_ID" \
    --query 'InstanceInformationList[0].PingStatus' --output text 2>/dev/null || true)
  [[ "$ping" == "Online" ]] && break
  sleep 2
done
[[ "$ping" == "Online" ]] || { echo "SSM Online 아님: $ping" >&2; exit 1; }
echo "Online: $(date '+%T')"

LOOP='mkdir -p /tmp/boot-capture
echo "capture_start=$(date -u +%FT%TZ) boot=$(uptime -s)" > /tmp/boot-capture/_capture.meta
end=$(( $(date +%s) + 360 ))
while [ $(date +%s) -lt $end ]; do
  # 부하를 줄이기 위해 10초 간격, 현재 배포 이미지(bc294b4…)가 아닌 컨테이너만 저장한다.
  for id in $(docker ps -q 2>/dev/null); do
    img=$(docker inspect -f "{{.Config.Image}}" $id 2>/dev/null)
    case "$img" in *"${CURRENT_TAG}"*) continue ;; esac
    docker inspect -f "{{.Id}} name={{.Name}} image={{.Config.Image}} created={{.Created}} started={{.State.StartedAt}} restart={{.HostConfig.RestartPolicy.Name}}" $id > /tmp/boot-capture/$id.meta 2>&1
    docker logs --timestamps --tail 3000 $id > /tmp/boot-capture/$id.log.tmp 2>&1 && mv /tmp/boot-capture/$id.log.tmp /tmp/boot-capture/$id.log
  done
  docker ps -a --format "{{.ID}} {{.Names}} {{.Image}} {{.Status}}" >> /tmp/boot-capture/_ps.txt 2>&1
  echo "-- $(date -u +%T)" >> /tmp/boot-capture/_ps.txt
  sleep 10
done
echo "capture_end=$(date -u +%FT%TZ)" >> /tmp/boot-capture/_capture.meta'
LOOP="CURRENT_TAG=${CURRENT_TAG:-bc294b4a1e96519b579478466fb68d36e5145db0}
$LOOP"

params=$(python3 -c 'import json,sys; print(json.dumps({"commands": [sys.argv[1]], "executionTimeout": ["600"]}))' "$LOOP")
cmd_id=$(aws_ ssm send-command --instance-ids "$INSTANCE_ID" --document-name AWS-RunShellScript \
  --parameters "$params" --query 'Command.CommandId' --output text)
echo "캡처 시작: command=$cmd_id (6분간 실행)"
