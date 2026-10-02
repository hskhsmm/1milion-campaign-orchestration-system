#!/usr/bin/env bash
# 부하 테스트 증거 수집 스크립트 공용 함수.
# 로컬에서 AWS API를 호출하고, 실제 명령은 SSM SendCommand로 EC2 안에서 실행한다.

AWS_REGION="${AWS_REGION:-ap-northeast-2}"
MCP_NAME="terraform-mcp"
APP_ASG="batch-kafka-app-asg"
REDIS_ADDR_PARAM="/batch-kafka/prod/REDIS_EXPORTER_ADDR"

aws_() { aws --region "$AWS_REGION" "$@"; }

# running 상태의 terraform-mcp InstanceId
mcp_instance_id() {
  local id
  id=$(aws_ ec2 describe-instances \
    --filters "Name=tag:Name,Values=$MCP_NAME" "Name=instance-state-name,Values=running" \
    --query 'Reservations[].Instances[].InstanceId' --output text)
  if [[ -z "$id" || "$id" == *$'\t'* ]]; then
    echo "running 상태의 $MCP_NAME EC2가 정확히 1대여야 합니다. 현재: '$id'" >&2
    return 1
  fi
  echo "$id"
}

# 앱 ASG에 붙은 InService/Pending 인스턴스 전부
app_instance_ids() {
  aws_ autoscaling describe-auto-scaling-groups --auto-scaling-group-names "$APP_ASG" \
    --query 'AutoScalingGroups[0].Instances[].InstanceId' --output text
}

# Redis configuration endpoint host (redis://host:6379 → host)
redis_host() {
  local addr
  addr=$(aws_ ssm get-parameter --name "$REDIS_ADDR_PARAM" --with-decryption --query 'Parameter.Value' --output text)
  addr="${addr#redis://}"
  echo "${addr%%:*}"
}

# ssm_run <instance-id> <shell command> → 명령 stdout 출력, 실패 시 stderr 출력 후 비정상 종료
ssm_run() {
  local instance_id="$1" command="$2" params command_id inv_status
  params=$(python3 -c 'import json,sys; print(json.dumps({"commands": [sys.argv[1]]}))' "$command")
  command_id=$(aws_ ssm send-command --instance-ids "$instance_id" \
    --document-name AWS-RunShellScript --parameters "$params" \
    --query 'Command.CommandId' --output text) || return 1

  for _ in $(seq 1 120); do
    sleep 1
    inv_status=$(aws_ ssm get-command-invocation --command-id "$command_id" --instance-id "$instance_id" \
      --query 'Status' --output text 2>/dev/null || true)
    case "$inv_status" in
      Success) break ;;
      Failed|Cancelled|TimedOut)
        aws_ ssm get-command-invocation --command-id "$command_id" --instance-id "$instance_id" \
          --query 'StandardErrorContent' --output text >&2
        return 1 ;;
    esac
  done
  [[ "$inv_status" == "Success" ]] || { echo "SSM 명령 대기 시간 초과: $command_id" >&2; return 1; }

  aws_ ssm get-command-invocation --command-id "$command_id" --instance-id "$instance_id" \
    --query 'StandardOutputContent' --output text
}

# ssm_fetch <instance-id> <원격에서 출력을 만드는 명령> <로컬 저장 경로>
# SSM 출력은 24,000자에서 잘리므로 gzip+base64로 만든 뒤 20,000자씩 나눠 받는다.
ssm_fetch() {
  local instance_id="$1" producer="$2" out="$3" tmp size offset=1 chunk=20000
  tmp="/tmp/ssm-fetch-$$"
  size=$(ssm_run "$instance_id" "( $producer ) 2>&1 | gzip -c | base64 -w0 > $tmp.b64 && stat -c %s $tmp.b64" | tr -d '[:space:]')
  [[ "$size" =~ ^[0-9]+$ ]] || { echo "원격 파일 생성 실패: $size" >&2; return 1; }

  : > "$out.b64"
  while (( offset <= size )); do
    ssm_run "$instance_id" "tail -c +$offset $tmp.b64 | head -c $chunk" | tr -d '[:space:]' >> "$out.b64"
    offset=$(( offset + chunk ))
    printf '\r  %s: %d/%d bytes' "$(basename "$out")" "$(( offset > size ? size : offset - 1 ))" "$size" >&2
  done
  echo >&2
  ssm_run "$instance_id" "rm -f $tmp.b64" >/dev/null || true
  base64 -d -i "$out.b64" | gunzip -c > "$out"
  rm -f "$out.b64"
}
