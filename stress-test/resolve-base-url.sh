#!/usr/bin/env bash
# 현재 AWS 계정의 ALB DNS를 조회한다. ALB가 없거나 아직 active가 아니면 실패한다.
set -euo pipefail

AWS_REGION=${AWS_REGION:-ap-northeast-2}
ALB_NAME=${ALB_NAME:-alb-batch-kafka-api}
EXPECTED_AWS_ACCOUNT_ID=${EXPECTED_AWS_ACCOUNT_ID:-631124976154}

account_id=$(aws sts get-caller-identity --query Account --output text)
if [[ "$account_id" != "$EXPECTED_AWS_ACCOUNT_ID" ]]; then
  echo "잘못된 AWS 계정: expected=$EXPECTED_AWS_ACCOUNT_ID actual=$account_id" >&2
  exit 1
fi

if ! alb_info=$(aws elbv2 describe-load-balancers \
  --region "$AWS_REGION" \
  --names "$ALB_NAME" \
  --query 'LoadBalancers[0].[DNSName,State.Code]' \
  --output text); then
  echo "ALB 조회 실패: name=$ALB_NAME region=$AWS_REGION" >&2
  exit 1
fi

read -r dns_name state <<< "$alb_info"
if [[ "$state" != "active" || -z "$dns_name" || "$dns_name" == "None" || "$dns_name" != *.elb.amazonaws.com ]]; then
  echo "사용 가능한 ALB가 아님: name=$ALB_NAME state=${state:-unknown} dns=${dns_name:-none}" >&2
  exit 1
fi

printf 'http://%s\n' "$dns_name"
