#!/usr/bin/env bash
set -euo pipefail

echo "[applicationStart] Start"

APP_DIR=/opt/campaign-core
COMPOSE_FILE="${APP_DIR}/docker-compose.prod.yml"
ENV_FILE="${APP_DIR}/.env.prod"

DEPLOY_SRC="/opt/codedeploy-agent/deployment-root/${DEPLOYMENT_GROUP_ID}/${DEPLOYMENT_ID}/deployment-archive/deploy/docker-compose.prod.yml"

if [[ ! -f "${DEPLOY_SRC}" ]]; then
  echo "[applicationStart] ERROR: docker-compose.prod.yml not found in bundle" >&2
  exit 1
fi

cp -f "${DEPLOY_SRC}" "${COMPOSE_FILE}"

if [[ -z "${ECR_IMAGE:-}" ]]; then
  if [[ -f "${ENV_FILE}" ]]; then
    ECR_IMAGE=$(grep '^ECR_IMAGE=' "${ENV_FILE}" | cut -d= -f2 || true)
    export ECR_IMAGE
  fi
fi

if [[ -z "${ECR_IMAGE:-}" ]]; then
  echo "[applicationStart] ERROR: ECR_IMAGE not defined" >&2
  exit 1
fi

echo "[applicationStart] Force removing existing container if exists"
docker rm -f campaign-core-app || true

echo "[applicationStart] Starting containers"
docker-compose -f "${COMPOSE_FILE}" --env-file "${ENV_FILE}" up -d --remove-orphans

echo "[applicationStart] Completed"
