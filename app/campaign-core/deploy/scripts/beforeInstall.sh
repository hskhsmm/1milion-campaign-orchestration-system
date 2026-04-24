#!/usr/bin/env bash
set -euo pipefail

echo "[beforeInstall] Start"

export AWS_REGION=ap-northeast-2

ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
ECR_URI="${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
export ECR_URI

echo "[beforeInstall] Logging into ECR: ${ECR_URI}"
aws ecr get-login-password --region "${AWS_REGION}" \
  | docker login --username AWS --password-stdin "${ECR_URI}"

APP_DIR=/opt/campaign-core
ENV_FILE=${APP_DIR}/.env.prod
mkdir -p "${APP_DIR}"
: > "${ENV_FILE}"

echo "[beforeInstall] Fetching SSM parameters -> ${ENV_FILE}"

fetch_param() {
  local key="$1" name="$2"
  local val
  val=$(aws ssm get-parameter --name "$name" --with-decryption \
        --query Parameter.Value --output text 2>/dev/null || true)
  if [[ -n "$val" && "$val" != "None" ]]; then
    echo "$key=$val" >> "${ENV_FILE}"
  else
    echo "[beforeInstall] WARN: Missing SSM parameter $name"
  fi
}

fetch_param SPRING_PROFILES_ACTIVE        "/batch-kafka/prod/SPRING_PROFILES_ACTIVE"
fetch_param SPRING_DATASOURCE_URL         "/batch-kafka/prod/SPRING_DATASOURCE_URL"
fetch_param SPRING_DATASOURCE_USERNAME    "/batch-kafka/prod/SPRING_DATASOURCE_USERNAME"
fetch_param SPRING_DATASOURCE_PASSWORD    "/batch-kafka/prod/SPRING_DATASOURCE_PASSWORD"
fetch_param SPRING_KAFKA_BOOTSTRAP_SERVERS "/batch-kafka/prod/SPRING_KAFKA_BOOTSTRAP_SERVERS"
fetch_param SPRING_DATA_REDIS_CLUSTER_NODES "/batch-kafka/prod/SPRING_DATA_REDIS_CLUSTER_NODES"
fetch_param REDIS_EXPORTER_ADDR            "/batch-kafka/prod/REDIS_EXPORTER_ADDR"
fetch_param SLACK_WEBHOOK_URL             "/batch-kafka/prod/SLACK_WEBHOOK_URL"
fetch_param ECR_IMAGE                     "/batch-kafka/prod/ECR_IMAGE"

chmod 600 "${ENV_FILE}"
echo "[beforeInstall] Completed"
