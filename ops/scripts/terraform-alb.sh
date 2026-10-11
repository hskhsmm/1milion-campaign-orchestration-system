#!/usr/bin/env bash
# env-up/down에서 ALB와 HTTP Listener만 저장된 Terraform plan으로 적용한다.
set -euo pipefail
umask 077

if [[ $# -ne 6 ]]; then
  echo "사용법: $0 up|down <infra-dir> <account-id> <vpc-id> <subnet-id> <region>" >&2
  exit 2
fi

mode=$1
terraform_dir=$2
account_id=$3
vpc_id=$4
subnet_id=$5
region=$6

[[ "$mode" == up || "$mode" == down ]] || { echo "up 또는 down만 허용합니다" >&2; exit 2; }
[[ "$account_id" =~ ^[0-9]{12}$ ]] || { echo "잘못된 account-id" >&2; exit 2; }
[[ "$vpc_id" =~ ^vpc-[0-9a-f]+$ ]] || { echo "잘못된 vpc-id" >&2; exit 2; }
[[ "$subnet_id" =~ ^subnet-[0-9a-f]+$ ]] || { echo "잘못된 subnet-id" >&2; exit 2; }
[[ "$region" =~ ^[a-z]{2}-[a-z]+-[0-9]+$ ]] || { echo "잘못된 region" >&2; exit 2; }

for command_name in aws terraform jq; do
  command -v "$command_name" >/dev/null || { echo "$command_name 명령이 필요합니다" >&2; exit 2; }
done

actual_account_id=$(aws sts get-caller-identity --query Account --output text)
[[ "$actual_account_id" == "$account_id" ]] || {
  echo "AWS 계정 불일치: expected=$account_id actual=$actual_account_id" >&2
  exit 1
}

if [[ "$mode" == up ]]; then
  alb_enabled=true
  expected_action=create
  target_args=(-target=aws_lb.main -target=aws_lb_listener.http -target=aws_iam_role_policy.terraform_mcp_alb_describe)
else
  alb_enabled=false
  expected_action=delete
  target_args=(-target=aws_lb.main -target=aws_lb_listener.http)
fi

plan_file=$(mktemp "${TMPDIR:-/tmp}/alb-plan.XXXXXX")
trap 'rm -f -- "$plan_file"' EXIT

terraform -chdir="$terraform_dir" init -input=false -no-color
terraform -chdir="$terraform_dir" plan -input=false -lock-timeout=30s -no-color \
  "-var=account_id=$account_id" "-var=vpc_id=$vpc_id" "-var=subnet_id=$subnet_id" \
  "-var=region=$region" "-var=alb_enabled=$alb_enabled" \
  "${target_args[@]}" -out="$plan_file"

# -target이 다른 자원을 함께 계획하거나 교체를 요구하면 실제 적용 전에 중단한다.
if ! terraform -chdir="$terraform_dir" show -json "$plan_file" | jq -e --arg expected_action "$expected_action" '
  [ .resource_changes[]? | select(.change.actions != ["no-op"]) ] as $changes
  | all($changes[];
      (.address == "aws_lb.main[0]" or .address == "aws_lb_listener.http[0]"
        or ($expected_action == "create" and .address == "aws_iam_role_policy.terraform_mcp_alb_describe"))
      and (.change.actions == [$expected_action])
    )
    and (($changes | map(.address) | length) == ($changes | map(.address) | unique | length))
' >/dev/null; then
  echo "ALB plan에 예상 밖의 변경이 있어 적용을 중단합니다" >&2
  exit 1
fi

terraform -chdir="$terraform_dir" apply -input=false -lock-timeout=30s -no-color "$plan_file"

if [[ "$mode" == up ]]; then
  terraform -chdir="$terraform_dir" output -raw alb_base_url
fi
