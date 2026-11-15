#!/usr/bin/env bash
# 참여 토픽(campaign-participation-topic)의 보관 제한을 조회하거나 적용한다. 실환경 Kafka 설정 변경이다.
#
# 배경 (2026-11-11): 브로커 기본값이 log.retention.hours=168, segment.bytes=1GB라 부하 시험 데이터가
# 거의 지워지지 않고 쌓여 루트 8GB 디스크가 찼다(kafka-1 정지, 나머지 98~99%).
# 브로커 3대가 파티션 10개를 모두 복제(RF=3)하므로 브로커 한 대가 토픽 전체를 들고 있다.
#
# 적용 값 (파티션마다):
#   retention.ms    = 86400000  (1일)
#   retention.bytes = 314572800 (300MB)  → 브로커당 최대 약 10 × (300MB + 활성 세그먼트 100MB) = 4GB
#   segment.bytes   = 104857600 (100MB)  → 보관 제한이 실제로 지워지도록 세그먼트를 작게
# 주의: retention.bytes를 넘으면 소비되지 않은 메시지도 지워진다. 300MB/파티션은 이벤트 약 50바이트 기준
#       파티션당 약 600만 건 밀림까지 버티는 크기다. DLQ 토픽은 재처리용이라 건드리지 않는다.
#
# 사용법: bash ops/scripts/kafka-topic-retention.sh            # 현재 설정 조회만
#         bash ops/scripts/kafka-topic-retention.sh --apply    # 위 값 적용 후 다시 조회
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/lib-ssm.sh"

TOPIC=campaign-participation-topic
CONFIGS="retention.ms=86400000,retention.bytes=314572800,segment.bytes=104857600"
APPLY=0; [[ "${1:-}" == "--apply" ]] && APPLY=1

APP=$(app_instance_ids "$APP_ASG" | awk '{print $1}')
[[ -n "$APP" ]] || { echo "Kafka 도구를 실행할 앱 인스턴스가 없습니다(앱 서버만 브로커에 접근 가능)." >&2; exit 1; }

remote=$(cat <<EOF
set -euo pipefail
B=\$(grep -m1 -o 'KAFKA_BOOTSTRAP_SERVERS=[^ ]*' /opt/campaign-core/.env.prod | cut -d= -f2-)  # 키 앞에 접두어가 붙어 있어 -o로 뽑는다
K="docker run --rm --network host -e KAFKA_HEAP_OPTS=-Xmx256m apache/kafka:3.9.1 /opt/kafka/bin"
docker pull -q apache/kafka:3.9.1 >/dev/null
if [ $APPLY = 1 ]; then
  \$K/kafka-configs.sh --bootstrap-server "\$B" --alter --entity-type topics --entity-name $TOPIC --add-config $CONFIGS 2>&1 | grep -v WARN
fi
\$K/kafka-configs.sh --bootstrap-server "\$B" --describe --entity-type topics --entity-name $TOPIC 2>&1 | grep -v WARN
EOF
)
echo "대상 토픽: $TOPIC (도구 실행: $APP, 적용: $([[ $APPLY = 1 ]] && echo 예 || echo 아니오 - 조회만))"
ssm_run "$APP" "$remote"
