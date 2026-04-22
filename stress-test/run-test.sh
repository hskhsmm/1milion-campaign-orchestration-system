#!/bin/bash
# k6 부하 테스트 실행 스크립트
# 사용법: ./run-test.sh [환경] [옵션]
#
# 환경:
#   local  — localhost:8080 (기본값)
#   prod   — AWS ALB
#
# 예시:
#   ./run-test.sh local
#   ./run-test.sh prod
#   CAMPAIGN_ID=2 TOTAL_REQUESTS=30000 ./run-test.sh prod

ENV=${1:-"local"}

# 환경별 BASE_URL
if [ "$ENV" = "prod" ]; then
  BASE_URL="http://alb-batch-kafka-api-1351817547.ap-northeast-2.elb.amazonaws.com"
else
  BASE_URL="http://localhost:8080"
fi

# 파라미터 (환경변수로 오버라이드 가능)
CAMPAIGN_ID=${CAMPAIGN_ID:-1}
TOTAL_REQUESTS=${TOTAL_REQUESTS:-15000}
MAX_VUS=${MAX_VUS:-1000}
DURATION=${DURATION:-60}

echo "=============================="
echo " k6 load test start"
echo " ENV           : $ENV"
echo " BASE_URL      : $BASE_URL"
echo " CAMPAIGN_ID   : $CAMPAIGN_ID"
echo " TOTAL_REQUESTS: $TOTAL_REQUESTS"
echo " MAX_VUS       : $MAX_VUS"
echo " DURATION      : ${DURATION}s"
echo "=============================="

k6 run \
  -e BASE_URL=$BASE_URL \
  -e CAMPAIGN_ID=$CAMPAIGN_ID \
  -e TOTAL_REQUESTS=$TOTAL_REQUESTS \
  -e MAX_VUS=$MAX_VUS \
  -e DURATION=$DURATION \
  "$(dirname "$0")/k6-load-test.js"
