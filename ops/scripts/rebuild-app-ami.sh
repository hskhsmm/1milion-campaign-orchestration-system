#!/usr/bin/env bash
# 앱 ASG용 AMI를 잔존 컨테이너·이미지 없이 다시 만든다.
#
# 배경: 기존 AMI(ami-01c64e7a84a57e681)에 restart=unless-stopped인 구버전 앱 컨테이너가 구워져 있어
# 새 인스턴스 부팅 시 자동 기동되고 active:campaigns를 조작했다
# (docs/current/2026-09-28-150m-reproduction-set-removal-trace.md).
#
# 절차:
#   1. Launch Template(batch-kafka-app-lt, $Latest)에 boothook이 들어 있는지 확인
#      (없으면 임시 인스턴스에서 구버전 컨테이너가 떠 prod Redis에 붙으므로 중단)
#   2. 운영 앱 SG 대신 임시 격리 SG(인바운드 없음, HTTP/S 아웃바운드만)를 만들어
#      같은 LT로 ASG 밖에 임시 인스턴스 1대 기동 (CodeDeploy 배포 대상 아님)
#   3. cloud-init 완료 대기 → 컨테이너·이미지 0개 확인 → 배포 잔재·마커·cloud-init 기록 정리
#   4. 인스턴스 정지 → create-image → available 대기 → 임시 인스턴스 종료
#   5. 새 AMI ID 출력 → infra/variables.tf의 app_ami_id 기본값을 PR에 기록하고
#      Launch Template만 plan/apply (사람이 실행)
#
# 사용법:
#   ./ops/scripts/rebuild-app-ami.sh            # 계획만 출력 (AWS 변경 없음)
#   CONFIRM=yes ./ops/scripts/rebuild-app-ami.sh # 실제 실행
set -euo pipefail
source "$(dirname "$0")/lib-ssm.sh"

LT_NAME="batch-kafka-app-lt"
BUILDER_NAME="batch-kafka-app-ami-builder"
AMI_NAME="batch-kafka-app-ami-$(date +%Y%m%d-%H%M)"
BUILDER_SG_NAME="batch-kafka-app-ami-builder-$(date +%Y%m%d-%H%M%S)-$$"
builder_sg_id=""
instance_id=""

cleanup() {
  if [[ -n "$instance_id" ]]; then
    echo "임시 인스턴스 종료: $instance_id"
    aws_ ec2 terminate-instances --instance-ids "$instance_id" >/dev/null || true
    aws_ ec2 wait instance-terminated --instance-ids "$instance_id" || true
  fi
  if [[ -n "$builder_sg_id" ]]; then
    echo "임시 격리 보안그룹 삭제: $builder_sg_id"
    aws_ ec2 delete-security-group --group-id "$builder_sg_id" >/dev/null || true
  fi
}
trap cleanup EXIT

lt_data() {
  aws_ ec2 describe-launch-template-versions --launch-template-name "$LT_NAME" --versions '$Latest' \
    --query "LaunchTemplateVersions[0].LaunchTemplateData.$1" --output text
}

lt_version=$(aws_ ec2 describe-launch-template-versions --launch-template-name "$LT_NAME" --versions '$Latest' \
  --query 'LaunchTemplateVersions[0].VersionNumber' --output text)
base_ami=$(lt_data ImageId)
subnet_id=$(aws_ autoscaling describe-auto-scaling-groups --auto-scaling-group-names "$APP_ASG" \
  --query 'AutoScalingGroups[0].VPCZoneIdentifier' --output text | cut -d, -f1)
vpc_id=$(aws_ ec2 describe-subnets --subnet-ids "$subnet_id" \
  --query 'Subnets[0].VpcId' --output text)

echo "Launch Template: $LT_NAME v$lt_version"
echo "기준 AMI:        $base_ami"
echo "임시 인스턴스 서브넷: $subnet_id"
echo "새 AMI 이름:     $AMI_NAME"

if ! lt_data UserData | base64 -d 2>/dev/null | grep -q 'text/cloud-boothook'; then
  echo "중단: LT \$Latest user-data에 boothook이 없습니다. infra에서 terraform apply로 LT를 먼저 갱신하세요." >&2
  exit 1
fi
echo "boothook 확인: OK"

if [[ "${CONFIRM:-}" != "yes" ]]; then
  echo
  echo "계획 출력만 했습니다. 실행하려면 CONFIRM=yes 를 붙이세요."
  exit 0
fi

builder_sg_id=$(aws_ ec2 create-security-group --vpc-id "$vpc_id" \
  --group-name "$BUILDER_SG_NAME" \
  --description 'Temporary isolated app AMI builder; no Redis/RDS/Kafka access' \
  --tag-specifications "ResourceType=security-group,Tags=[{Key=Name,Value=$BUILDER_SG_NAME},{Key=Purpose,Value=app-ami-build}]" \
  --query GroupId --output text)
# SG 기본 아웃바운드 전체 허용을 제거한다. 인스턴스는 이 단계가 끝난 뒤에만 기동한다.
aws_ ec2 revoke-security-group-egress --group-id "$builder_sg_id" \
  --ip-permissions '[{"IpProtocol":"-1","IpRanges":[{"CidrIp":"0.0.0.0/0"}]}]' >/dev/null
aws_ ec2 authorize-security-group-egress --group-id "$builder_sg_id" \
  --ip-permissions '[{"IpProtocol":"tcp","FromPort":80,"ToPort":80,"IpRanges":[{"CidrIp":"0.0.0.0/0"}]},{"IpProtocol":"tcp","FromPort":443,"ToPort":443,"IpRanges":[{"CidrIp":"0.0.0.0/0"}]}]' >/dev/null
echo "격리 보안그룹: $builder_sg_id (인바운드 없음, 아웃바운드 80/443만 허용)"

instance_id=$(aws_ ec2 run-instances \
  --launch-template "LaunchTemplateName=$LT_NAME,Version=$lt_version" \
  --network-interfaces "DeviceIndex=0,SubnetId=$subnet_id,Groups=$builder_sg_id,AssociatePublicIpAddress=true" \
  --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$BUILDER_NAME}]" \
  --query 'Instances[0].InstanceId' --output text)
echo "임시 인스턴스 기동: $instance_id ($(date '+%T'))"

echo "SSM Online 대기"
ping=""
for _ in $(seq 1 120); do
  ping=$(aws_ ssm describe-instance-information --filters "Key=InstanceIds,Values=$instance_id" \
    --query 'InstanceInformationList[0].PingStatus' --output text 2>/dev/null || true)
  [[ "$ping" == "Online" ]] && break
  sleep 5
done
[[ "$ping" == "Online" ]] || { echo "SSM Online 아님: $ping" >&2; exit 1; }

echo "cloud-init 완료 대기"
ssm_run "$instance_id" 'timeout 110 cloud-init status --wait >/dev/null; cloud-init status' || true
ssm_run "$instance_id" 'timeout 110 cloud-init status --wait; cloud-init status --long'

echo "boothook / user-data 기록"
ssm_run "$instance_id" 'cat /var/log/batch-kafka-app-boothook.log; grep -E "containers before cleanup|container cleanup" -A5 /var/log/batch-kafka-app-user-data.log'

echo "잔존 컨테이너·이미지 확인 및 정리"
ssm_run "$instance_id" 'set -e
docker ps -aq | xargs -r docker rm -f
docker image prune -af >/dev/null
echo "containers=$(docker ps -aq | wc -l) images=$(docker images -q | wc -l)"
[ "$(docker ps -aq | wc -l)" -eq 0 ] && [ "$(docker images -q | wc -l)" -eq 0 ]
rm -rf /opt/campaign-core /var/lib/batch-kafka-app /tmp/boot-capture
rm -f /var/log/batch-kafka-app-boothook.log /var/log/batch-kafka-app-user-data.log
cloud-init clean --logs
echo cleaned'

echo "인스턴스 정지"
aws_ ec2 stop-instances --instance-ids "$instance_id" >/dev/null
aws_ ec2 wait instance-stopped --instance-ids "$instance_id"

ami_id=$(aws_ ec2 create-image --instance-id "$instance_id" --name "$AMI_NAME" \
  --description "batch-kafka app AMI (Docker + CodeDeploy, no baked containers). base=$base_ami" \
  --tag-specifications "ResourceType=image,Tags=[{Key=Name,Value=$AMI_NAME}]" \
  --query 'ImageId' --output text)
echo "AMI 생성 요청: $ami_id ($(date '+%T'))"
aws_ ec2 wait image-available --image-ids "$ami_id"
echo "AMI available: $ami_id ($(date '+%T'))"

echo
echo "다음 단계 (사람이 실행):"
echo "  1. infra/variables.tf 의 app_ami_id 기본값을 \"$ami_id\" 로 갱신하고 PR에 기록"
echo "  2. cd infra && terraform plan -target=aws_launch_template.app 후 변경 범위 확인·적용"
echo "  3. 새 인스턴스 1대 기동 후 docker ps -a에 현재 앱 컨테이너만 있는지 확인"
