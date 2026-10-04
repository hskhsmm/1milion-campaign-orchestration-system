#!/usr/bin/env bash
# DB에 OPEN으로 남은 과거 부하 테스트 캠페인을 CLOSED로 정리한다.
#
# 배경: 과거 테스트 캠페인(1,2,3,8,9,13~19,25~54 등)이 DB에 OPEN으로 남아 있어, AMI 구버전 컨테이너의
# StockRecoveryScheduler가 부팅마다 Redis 재고·active Set을 되살렸다
# (docs/current/2026-09-28-150m-reproduction-set-removal-trace.md §5.3). 구버전 제거와 별개로
# 정합성 검사 대상·Set 순회 비용을 줄이기 위해 정리한다. 재고(current_stock)는 기록 보존을 위해 건드리지 않는다.
#
# 실행 위치: 앱 ASG 인스턴스 1대 (RDS 접근 가능한 SG). SSM으로 mysql:8.4 컨테이너를 띄워 실행한다.
#
# 사용법:
#   MAX_ID=64 ./ops/scripts/close-stale-campaigns.sh             # OPEN 캠페인 목록만 출력 (변경 없음)
#   MAX_ID=64 CONFIRM=yes ./ops/scripts/close-stale-campaigns.sh # id <= MAX_ID 인 OPEN 캠페인을 CLOSED로 변경
set -euo pipefail
source "$(dirname "$0")/lib-ssm.sh"

MAX_ID="${MAX_ID:?MAX_ID 필요: 이 id 이하의 OPEN 캠페인만 정리합니다}"
[[ "$MAX_ID" =~ ^[0-9]+$ ]] || { echo "MAX_ID는 숫자여야 합니다: $MAX_ID" >&2; exit 1; }

instance_id="${INSTANCE_ID:-$(app_instance_ids | awk '{print $1}')}"
[[ -n "$instance_id" ]] || { echo "앱 ASG 인스턴스가 없습니다 (make env-up 필요)" >&2; exit 1; }
echo "실행 인스턴스: $instance_id"

if [[ "${CONFIRM:-}" == "yes" ]]; then
  sql="UPDATE campaign SET status='CLOSED', updated_at=NOW(6) WHERE status='OPEN' AND id <= ${MAX_ID}; SELECT ROW_COUNT() AS closed;"
else
  sql="SELECT id, name, total_stock, current_stock, status, created_at FROM campaign WHERE status='OPEN' AND id <= ${MAX_ID} ORDER BY id;"
fi

# .env.prod는 배포 playbook이 SSM Parameter Store에서 만든 따옴표 없는 KEY=VALUE 파일이다.
# JDBC URL의 '&' 등 때문에 source하지 않고 값만 잘라 읽는다.
remote=$(cat <<EOF
set -euo pipefail
env_get() { grep -m1 "^\$1=" /opt/campaign-core/.env.prod | cut -d= -f2-; }
url="\$(env_get SPRING_DATASOURCE_URL)"; url="\${url#jdbc:mysql://}"
hostport="\${url%%/*}"; db="\${url#*/}"; db="\${db%%\?*}"
host="\${hostport%%:*}"; port="\${hostport##*:}"; [ "\$port" = "\$host" ] && port=3306
docker run --rm -e MYSQL_PWD="\$(env_get SPRING_DATASOURCE_PASSWORD)" mysql:8.4 \
  mysql -h "\$host" -P "\$port" -u "\$(env_get SPRING_DATASOURCE_USERNAME)" "\$db" --table -e "$sql"
EOF
)

ssm_run "$instance_id" "$remote"

if [[ "${CONFIRM:-}" != "yes" ]]; then
  echo
  echo "조회만 했습니다. id <= $MAX_ID 인 OPEN 캠페인을 CLOSED로 바꾸려면 CONFIRM=yes 를 붙이세요."
fi
