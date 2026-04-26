#!/usr/bin/env bash
set -euo pipefail

echo "[applicationStart] Start"

APP_DIR=/opt/campaign-core
COMPOSE_FILE="${APP_DIR}/docker-compose.prod.yml"
ENV_FILE="${APP_DIR}/.env.prod"
STRESS_DIR="${APP_DIR}/stress-test"

DEPLOY_SRC="/opt/codedeploy-agent/deployment-root/${DEPLOYMENT_GROUP_ID}/${DEPLOYMENT_ID}/deployment-archive/deploy/docker-compose.prod.yml"
STRESS_SRC="/opt/codedeploy-agent/deployment-root/${DEPLOYMENT_GROUP_ID}/${DEPLOYMENT_ID}/deployment-archive/stress-test"

if [[ ! -f "${DEPLOY_SRC}" ]]; then
  echo "[applicationStart] ERROR: docker-compose.prod.yml not found in bundle" >&2
  exit 1
fi

cp -f "${DEPLOY_SRC}" "${COMPOSE_FILE}"

if [[ -d "${STRESS_SRC}" ]]; then
  echo "[applicationStart] Syncing stress-test scripts"
  rm -rf "${STRESS_DIR}"
  cp -R "${STRESS_SRC}" "${STRESS_DIR}"
  chmod -R a+rX "${STRESS_DIR}"
fi

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

echo "[applicationStart] Stopping existing containers"
docker-compose -f "${COMPOSE_FILE}" down --remove-orphans 2>/dev/null || true
docker rm -f campaign-core-app redis-exporter 2>/dev/null || true

echo "[applicationStart] Starting containers"
docker-compose -f "${COMPOSE_FILE}" --env-file "${ENV_FILE}" up -d --remove-orphans

echo "[applicationStart] Completed"
