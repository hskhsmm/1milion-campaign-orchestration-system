# Bridge 병목 관측과 로컬 검증

## 목적과 경계

#114의 가설은 순차 Redis claim의 클라이언트 대기가 Bridge 처리량을 제한한다는 것이다. 아직 AWS에서 확인한 원인은 아니다. 먼저 Redis 호출과 Kafka send 대기를 구분하며 Lua·배치 크기·주기·in-flight 상한·ACK 실행기 설정은 유지한다.

## 계측 의미

| 메트릭 | 단위·범위 |
| --- | --- |
| bridge.redis.duration, operation=claim/ack/reclaim | 클라이언트 호출 시간(초), 성공·예외 모두 timer count에 포함. 연결·네트워크·직렬화 및 스크립트 캐시 fallback 등이 포함될 수 있어 Redis 서버 실행 시간으로 해석하지 않음 |
| bridge.redis.errors, operation | Redis 호출이 RuntimeException으로 종료된 횟수. 예외는 원래대로 전파 |
| bridge.claim.results, result=claimed/not_claimed | 선점 성공/결과 없음 횟수. not_claimed는 빈 Queue와 in-flight 상한을 구분하지 못함 |
| bridge.kafka.send.duration | KafkaTemplate.send 동기 호출 시간. 버퍼/메타데이터 대기를 포함할 수 있음 |
| bridge.kafka.confirm.duration | send 시작~성공/실패 응답까지. 동기 예외도 포함. Redis ACK·DB 완료가 아님 |
| 기존 bridge.drain.duration | 한 스케줄 실행의 active Set 조회·캠페인 순회·동기 claim/send까지. 비동기 ACK 완료와 스케줄 사이 100ms 대기는 제외 |

새 지표에는 campaignId/userId/claim token 태그를 붙이지 않는다. operation은 3개, result는 2개뿐이다. Redis/SEND histogram은 고정 SLO 경계만 사용한다. 메트릭 수집을 위해 Redis 명령·Kafka 메시지를 추가하지 않는다. timer count는 논리 호출 수이며 EVALSHA 캐시 miss 시 실제 wire 명령 수와 다를 수 있다.

Prometheus에서는 점이 밑줄로, Timer는 `_seconds_count/sum/bucket`, Counter는 `_total`로 노출된다. 구체 조회식은 [조회 목록](../../stress-test/bridge-observation-queries.tsv)에 있다. 평균은 sum/count의 가중 평균, p95는 bucket 추정이다. 관측이 없는 구간의 NaN/No data를 0ms로 오인하지 않는다. 긴 꼬리는 마지막 유한 bucket(Redis 1초, send 60초) 밖에서 정확히 추정할 수 없다.

send와 응답 시간은 서로 겹친다. 둘을 합산하거나 별도 ACK 스레드의 시간을 drain 시간에 그대로 더해 E2E 지연으로 표현하지 않는다. 부하 발생 전후·drain 구간을 나눠 instance별로 비교한다.

## 로컬 검증

일반 테스트는 저장소 루트에서 실행한다.

```sh
bash app/campaign-core/gradlew -p app/campaign-core test --offline --no-daemon --max-workers=1
```

실제 Redis smoke는 격리한 localhost Redis에서만 실행한다. 이 테스트는 고유 캠페인 키를 생성하고 해당 키만 정리한다. FLUSHDB/FLUSHALL을 사용하지 않는다.

```sh
docker run -d --name campaign-bridge-observation-redis -p 127.0.0.1::6379 redis:7.2
# 컨테이너가 준비된 뒤 실행한다.
REDIS_TEST_PORT=$(docker port campaign-bridge-observation-redis 6379 | awk -F: '{print $NF}')
BRIDGE_TEST_REDIS_PORT="$REDIS_TEST_PORT" \
  bash app/campaign-core/gradlew -p app/campaign-core test --offline --no-daemon --max-workers=1
docker rm -f campaign-bridge-observation-redis
```

MySQL 환경 변수가 없으면 기존 DLQ DB 연동 10건은 건너뛴다. 이 실행을 MySQL/Kafka E2E 통과라고 표현하지 않는다. 별도 DB 시험 절차는 [DLQ 운영 가이드](dlq-replay.md)를 참고한다.

## AWS 재개 시 할 일

지금은 실행하지 않는 체크리스트다.

1. 이전 캠페인 잔류 키·메모리·Queue/in-flight/lag·활성 작업과 배포 버전을 확인한다. 키 정리는 별도 범위를 확인한다.
2. 동일 앱 수·사양·AZ·부하 조건과 데이터 초기 상태를 기록한다. 고정 인스턴스 수 비교와 스케일아웃 시험을 구분한다.
3. Grafana의 Bridge Redis 평균/p95, Kafka send/응답, claim 결과 패널과 조회 목록을 수집한다. 스크래핑 최소 2회 이상과 1분 rate 창을 고려한다.
4. Redis claim 지연 상승과 drain 변화, ACK 지연/오류, send 동기 대기, CPU·GC·SLOWLOG/LATENCY·AZ를 대조한다. 이 계측만으로 네트워크/서버 내부 원인을 확정하지 않는다.
5. 순차 왕복이 원인으로 확인됐을 때만 batch claim/pipelining을 검토한다. 실패·만료·늦은 ACK 경계 검증을 유지한다.

## 하네스 연결 상태

main에는 아직 stress-test/harness가 없다. 원격 feat/evidence-harness의 2c9f9e4 collect.sh에서는 `queue_gauge`를 `sum(redis_queue_size{campaignId=...})`으로 수집한다. #101 통합 시 `max by (campaignId)`로 바꾸고 이 조회 목록을 연결해야 한다. 실제 Redis LLEN을 최종 잔량 판단의 근거로 유지한다.

현재 Grafana·MCP는 이미 max by(campaignId)를 쓰므로 수정하지 않았다. 원격 하네스/WIP를 현재 성능 관측 브랜치에 통째로 병합하지 않았고 감시 스크립트·판정기 수정도 완료로 간주하지 않는다. JSON 구조는 로컬 확인했지만 Grafana 렌더링·Prometheus 구간 조회·실환경 스크래핑은 미검증이다.
