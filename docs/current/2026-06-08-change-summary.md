# 2026-06-08~09 Change Summary

이번 작업의 핵심은 v3 구조를 크게 바꾸는 것이 아니라, 현재 운영 흐름을 더 정확하게 관측하고, 더 이상 사용하지 않는 v1/v2 레거시 실행 코드를 정리하는 것이었다.

가장 중요한 결론부터 말하면, v3 핵심 정합성 경로는 그대로 유지된다.

```text
POST /api/campaigns/{id}/participation
-> ParticipationService
-> RateLimitService
-> RedisStockService
-> check-decr-enqueue.lua
-> Redis 재고 차감 + 순번 확정 + Queue 적재
-> 202 Accepted
-> ParticipationBridge
-> Kafka
-> ParticipationEventConsumer
-> MySQL participation_history 저장
```

삭제한 것은 현재 v3 운영 흐름에서 사용하지 않는 비교용/레거시 코드다.

- v1 비교용 동기 API: `/participation-sync`
- v1 전용 DB 원자 차감 메서드: `CampaignRepository.decreaseStockAtomic()`
- v2 PENDING 복구 Job: `PendingRecoveryJobConfig`
- `BatchScheduler`의 PENDING 복구 스케줄링

변경 후 `./gradlew.bat test` 기준으로 컴파일과 테스트가 통과했다.

## 1. 변경 파일

직접 변경한 실행 코드는 다음과 같다.

```text
app/campaign-core/src/main/java/io/eventdriven/campaign/application/consumer/ParticipationEventConsumer.java
app/campaign-core/src/main/java/io/eventdriven/campaign/api/controller/ParticipationController.java
app/campaign-core/src/main/java/io/eventdriven/campaign/batch/BatchScheduler.java
app/campaign-core/src/main/java/io/eventdriven/campaign/domain/repository/CampaignRepository.java
```

삭제한 실행 코드는 다음과 같다.

```text
app/campaign-core/src/main/java/io/eventdriven/campaign/batch/PendingRecoveryJobConfig.java
```

추가한 테스트와 문서는 다음과 같다.

```text
app/campaign-core/src/test/java/io/eventdriven/campaign/application/service/DlqReplayPolicyServiceTest.java
docs/current/reliability-improvements.md
docs/current/2026-06-08-change-summary.md
```

관측성 개선을 실제 Grafana 화면에서 바로 확인할 수 있도록 대시보드 파일도 업데이트했다.

```text
app/campaign-core/monitoring/grafana/dashboards/campaign.json
```

## 2. 정합성 영향 여부

이번 변경은 v3 정합성 핵심 로직을 바꾸지 않았다.

유지된 핵심 로직은 다음과 같다.

- Redis Lua에서 재고 차감, 중복 참여 방지, 순번 확정, Queue 적재를 하나의 원자적 실행 단위로 처리
- `{campaignId}` 해시태그로 Redis Cluster 환경에서도 Lua 대상 키들을 같은 슬롯에 배치
- Kafka 파티션 처리 순서와 무관하게 Redis에서 비즈니스 순번을 먼저 확정
- Consumer가 DB 저장 실패 시 Kafka ack를 보류해 재전달 가능하게 처리
- DLQ를 통해 publish 실패나 잘못된 메시지를 별도 격리

따라서 삭제된 v1/v2 레거시는 현재 v3 정합성에 영향을 주지 않는다.

정확히는 다음과 같이 볼 수 있다.

| 제거 대상 | 기존 용도 | v3 정합성 영향 |
| --- | --- | --- |
| `/participation-sync` | DB row lock 방식 성능 비교용 API | 없음 |
| `decreaseStockAtomic()` | `/participation-sync`에서만 사용하던 DB 원자 차감 | 없음 |
| `PendingRecoveryJobConfig` | v2 PENDING 상태 재발행 복구 Job | 없음 |
| `schedulePendingRecovery()` | PENDING 복구 Job 주기 실행 | 없음 |

v3에서는 API 응답 경로에서 DB PENDING INSERT를 하지 않으므로, PENDING 복구 Job은 현재 구조와 맞지 않는 레거시였다.

## 3. 202 이후 후단 TPS 지표 추가

### 기존 한계

기존 API TPS는 사용자가 `202 Accepted`를 받기까지의 처리량을 의미했다.

현재 v3 구조에서 `202 Accepted`는 다음 작업이 끝났다는 뜻이다.

```text
HTTP 요청
-> Redis Lua 실행
-> 재고 차감
-> 순번 확정
-> Redis Queue 적재
-> 202 Accepted 반환
```

이 시점에는 아직 MySQL 저장이 완료된 것이 아니다.

실제 DB 반영은 다음 후단 흐름에서 비동기로 처리된다.

```text
Redis Queue
-> ParticipationBridge
-> Kafka
-> ParticipationEventConsumer
-> MySQL participation_history 저장
```

따라서 API TPS만 보면 사용자 응답 성능은 알 수 있지만, 실제 후단 처리량과 DB 저장 처리량은 분리해서 보기 어렵다.

### 추가한 메트릭

`ParticipationEventConsumer`에 다음 Micrometer 지표를 추가했다.

```text
consumer.kafka.records.polled
consumer.events.parsed
consumer.db.committed
consumer.db.commit.batch.size
consumer.db.transient.failures
```

각 지표의 의미는 다음과 같다.

| 지표 | 의미 |
| --- | --- |
| `consumer.kafka.records.polled` | Kafka Consumer가 poll해서 가져온 record 수 |
| `consumer.events.parsed` | JSON 파싱과 필수값 검증을 통과한 ParticipationEvent 수 |
| `consumer.db.committed` | DB 성공 처리 경로까지 도달한 이벤트 수 |
| `consumer.db.commit.batch.size` | Consumer 1회 처리에서 DB 성공 처리한 batch 크기 |
| `consumer.db.transient.failures` | DB 일시 장애 등으로 Kafka ack를 보류해야 하는 실패 발생 수 |

Prometheus에서는 다음처럼 TPS로 볼 수 있다.

```promql
rate(consumer_kafka_records_polled_total[1m])
rate(consumer_events_parsed_total[1m])
rate(consumer_db_committed_total[1m])
rate(consumer_db_transient_failures_total[1m])
```

Bridge 구간은 기존 지표를 그대로 사용한다.

```promql
rate(bridge_messages_published_total[1m])
```

이제 Grafana에서는 다음 흐름을 분리해서 볼 수 있다.

```text
API TPS
-> Redis Queue size
-> Bridge publish TPS
-> Kafka Consumer poll TPS
-> Consumer parse TPS
-> DB committed TPS
-> Kafka lag
```

이렇게 보면 병목 위치를 더 정확하게 판단할 수 있다.

- API TPS는 높은데 Redis Queue가 계속 증가한다: Bridge drain 속도가 유입 속도를 못 따라간다.
- Bridge publish TPS는 높은데 Kafka lag가 증가한다: Consumer 또는 DB 저장이 병목이다.
- Consumer poll TPS는 높은데 DB committed TPS가 낮다: 파싱 실패, DB 실패, batch insert 병목 가능성이 있다.
- DB transient failure가 증가한다: DB 장애나 커넥션 문제로 Kafka ack가 보류되고 있을 수 있다.

Grafana 대시보드에는 다음 패널을 추가했다.

| 패널 | 목적 |
| --- | --- |
| `Consumer 후단 처리량 (건/초)` | Kafka poll, 정상 parse, DB committed TPS를 한 화면에서 비교 |
| `Consumer DB 일시 실패율 (건/초)` | DB 장애 등으로 ack 보류가 필요한 transient failure 감지 |
| `Consumer DB commit batch size (평균)` | Consumer가 DB에 성공 처리한 평균 batch 크기 확인 |

## 4. DLQ 재처리 정책 테스트 추가

DLQ는 운영 관점에서 중요한 기능이다.

DLQ 메시지를 무조건 재처리하면 중복 처리나 잘못된 데이터 재유입이 발생할 수 있다. 그래서 재처리 전에 다음 판단이 필요하다.

- 이 메시지를 다시 처리해도 되는가?
- 이미 성공 처리된 메시지라면 중복 저장을 막을 수 있는가?
- 필수값이 깨진 메시지는 재처리하지 않고 최종 실패로 분류할 수 있는가?
- 존재하지 않는 캠페인의 메시지는 재처리 대상에서 제외할 수 있는가?

이를 검증하기 위해 `DlqReplayPolicyServiceTest`를 추가했다.

추가한 테스트는 다음 5개다.

| 테스트 | 검증 내용 |
| --- | --- |
| `decide_nonReplayable_finalFail` | `NON_REPLAYABLE` 메시지는 재처리하지 않고 최종 실패 |
| `decide_missingRequiredFields_finalFail` | `campaignId/userId/sequence` 중 하나라도 없으면 최종 실패 |
| `decide_campaignNotFound_finalFail` | 존재하지 않는 캠페인의 메시지는 최종 실패 |
| `decide_alreadySuccess_skip` | 이미 SUCCESS 기록이 있으면 중복 방지를 위해 skip |
| `decide_replayable_replay` | 재처리 가능한 메시지는 userId 키로 원래 토픽에 재발행하도록 결정 |

이 테스트는 Kafka나 DB를 실제로 띄우는 통합 테스트가 아니라, DLQ 재처리 정책 자체를 빠르게 검증하는 단위 테스트다.

면접용으로는 이렇게 설명할 수 있다.

> DLQ 메시지를 무조건 다시 보내면 중복 처리나 잘못된 데이터 재유입이 생길 수 있다고 봤습니다. 그래서 재처리 전에 메시지를 분류하고, 필수값 누락이나 존재하지 않는 캠페인은 최종 실패로 분리하고, 이미 SUCCESS 기록이 있는 메시지는 skip하도록 정책 테스트를 추가했습니다.

## 5. v1/v2 레거시 실행 코드 제거

### 5.1 `/participation-sync` 제거

`/participation-sync`는 DB row lock 방식의 성능 비교를 위해 남아 있던 v1 성격 API였다.

기존 흐름은 다음과 같았다.

```text
POST /api/campaigns/{id}/participation-sync
-> CampaignRepository.decreaseStockAtomic()
-> ParticipationHistory save
-> 200 OK
```

이 API는 현재 v3 운영 흐름과 다르다.

현재 핵심 API는 `/participation`이고, Redis Lua와 Kafka 비동기 구조를 사용한다.

따라서 현재 코드 품질 관점에서는 `/participation-sync`를 남겨두는 것이 오히려 혼란을 준다고 판단했다.

### 5.2 `decreaseStockAtomic()` 제거

`decreaseStockAtomic()`은 `/participation-sync`에서만 사용하던 DB 원자 차감 메서드다.

v3에서는 DB가 사용자 응답 경로에서 빠졌고, 재고 차감은 Redis Lua에서 처리한다.

따라서 이 메서드는 현재 구조에서 사용되지 않는 레거시였다.

### 5.3 `PendingRecoveryJobConfig` 제거

`PendingRecoveryJobConfig`는 v2 PENDING 구조를 전제로 한 복구 Job이다.

v2에서는 API 응답 경로에서 DB에 PENDING을 먼저 남기고, Consumer가 이후 SUCCESS로 바꾸는 구조를 고민했다.

하지만 v3에서는 API 응답 경로에서 DB를 제거했다.

현재 흐름은 다음과 같다.

```text
Redis Lua
-> Redis Queue
-> 202 Accepted
-> Bridge
-> Kafka
-> Consumer
-> INSERT SUCCESS
```

따라서 정상 v3 흐름에서는 API 단계에서 PENDING 데이터가 생성되지 않는다.

PENDING 복구 Job은 현재 구조와 맞지 않으므로 제거했다.

### 5.4 `BatchScheduler` 정리

`BatchScheduler`에서는 PENDING 복구 Job 주기 실행을 제거했다.

현재 남은 스케줄링은 다음 두 가지다.

- 일일 참여 집계 Job
- 오래된 Spring Batch 메타데이터 정리 Job

즉 현재 실제로 사용하는 batch 흐름만 남긴 상태다.

## 6. Redis Cluster와 Lua 원자성 한계

현재 Redis 키는 `{campaignId}` 해시태그를 사용한다.

예시:

```text
stock:campaign:{1}
total:campaign:{1}
queue:campaign:{1}
participated:campaign:{1}:user:42
active:campaign:{1}
```

이유는 Lua 스크립트가 여러 Redis 키를 한 번에 원자적으로 다뤄야 하기 때문이다.

Redis Cluster에서는 Lua 스크립트가 접근하는 키들이 서로 다른 슬롯에 있으면 실행할 수 없다.

현재 Lua 스크립트는 하나의 요청 안에서 다음 작업을 원자적으로 처리한다.

```text
캠페인 활성 여부 확인
-> 중복 참여 여부 확인
-> Queue full 여부 확인
-> 재고 차감
-> 순번 증가
-> Redis Queue 적재
```

이 작업들은 비즈니스적으로 하나의 실행 단위로 묶여야 한다.

그래서 같은 캠페인의 Redis 키들을 같은 슬롯에 묶었다.

장점은 다음과 같다.

- 재고 차감과 순번 확정이 원자적으로 처리된다.
- 중복 참여 방지와 Queue 적재가 같은 실행 단위로 묶인다.
- Kafka 처리 순서가 뒤섞여도 Redis에서 이미 비즈니스 순서가 확정된다.
- 여러 캠페인이 동시에 열리면 캠페인 단위로 슬롯 분산이 가능하다.

한계도 명확하다.

- 단일 대형 캠페인은 하나의 Redis 슬롯/샤드에 집중된다.
- Redis Cluster 전체 용량이 커져도 단일 캠페인 요청은 여러 샤드로 자동 분산되지 않는다.
- 한 샤드의 CPU, 메모리, 네트워크, Queue 크기가 단일 캠페인의 상한이 된다.

## 7. 다음 개선 방향

### 7.1 대기열 시스템

가장 현실적인 개선 방향은 API 앞단에 대기열 시스템을 두는 것이다.

대기열을 두면 모든 사용자가 동시에 Redis Lua 재고 차감 구간으로 진입하지 않고, 일정량만 순차적으로 핵심 처리 구간으로 들어오게 만들 수 있다.

효과:

- Redis 단일 슬롯에 순간 부하가 몰리는 문제 완화
- Queue full 가능성 완화
- 사용자에게 대기 순번/예상 대기 상태 제공 가능
- 안정적인 운영에 유리

단점:

- TPS 자체를 무조건 높이는 방식은 아니다.
- 순간 부하를 평탄화해서 시스템을 안정적으로 운영하는 전략에 가깝다.
- 후단 DB commit 처리량이 낮으면 최종 병목은 여전히 남는다.

### 7.2 Bucket 기반 재고 분산

더 공격적으로 단일 캠페인의 처리량을 높이려면 재고를 bucket 단위로 나누는 방법도 있다.

예시:

```text
stock:campaign:{1:0}
stock:campaign:{1:1}
stock:campaign:{1:2}
stock:campaign:{1:3}
```

사용자는 `userId % bucketCount` 같은 기준으로 bucket에 배정할 수 있다.

효과:

- 단일 캠페인 부하를 여러 Redis 슬롯/샤드로 분산 가능
- Lua 실행도 bucket 단위로 병렬화 가능

단점:

- 전체 재고를 bucket별로 어떻게 배분할지 정해야 한다.
- 특정 bucket만 먼저 소진되는 편차가 생길 수 있다.
- 전체 순번을 하나의 전역 sequence로 유지하려면 별도 설계가 필요하다.
- bucket 간 재고 재분배 로직이 필요할 수 있다.

### 7.3 Redis Queue -> Kafka 발행 구간 보강

현재 Bridge는 Redis Queue에서 `RPOP`으로 메시지를 꺼낸 뒤 Kafka로 발행한다.

Kafka 발행 실패는 DLQ로 보내도록 보강되어 있지만, 더 강한 전달 보장이 필요하면 Redis에서 꺼낸 메시지를 바로 삭제하지 않고 처리 중 상태로 분리하는 구조가 좋다.

후보:

- Redis Streams + Consumer Group
- `RPOPLPUSH` 기반 processing queue
- Outbox 테이블 기반 발행 보장

## 8. 검증 결과

변경 후 다음 명령으로 테스트를 실행했다.

```powershell
.\gradlew.bat test
```

실행 위치:

```text
app/campaign-core
```

결과:

```text
BUILD SUCCESSFUL
```

추가로 다음 사항을 확인했다.

- `participation-sync` 참조 없음
- `decreaseStockAtomic` 참조 없음
- `PendingRecoveryJobConfig` 참조 없음
- `pendingRecoveryJob` 참조 없음
- `schedulePendingRecovery` 참조 없음
- `git diff --check` 통과

## 9. 커밋 분리 추천

현재 변경은 다음 단위로 커밋을 나누는 것이 좋다.

```bash
git add app/campaign-core/src/main/java/io/eventdriven/campaign/application/consumer/ParticipationEventConsumer.java
git commit -m "feat: Consumer 후단 처리량 지표 추가"

git add app/campaign-core/src/test/java/io/eventdriven/campaign/application/service/DlqReplayPolicyServiceTest.java
git commit -m "test: DLQ 재처리 정책 단위 테스트 추가"

git add app/campaign-core/src/main/java/io/eventdriven/campaign/api/controller/ParticipationController.java
git add app/campaign-core/src/main/java/io/eventdriven/campaign/batch/BatchScheduler.java
git add app/campaign-core/src/main/java/io/eventdriven/campaign/batch/PendingRecoveryJobConfig.java
git add app/campaign-core/src/main/java/io/eventdriven/campaign/domain/repository/CampaignRepository.java
git commit -m "refactor: v1/v2 레거시 실행 코드 제거"

git add docs/current/reliability-improvements.md
git add docs/current/2026-06-08-change-summary.md
git commit -m "docs: 관측성 개선과 레거시 정리 내용 반영"
```

## 10. 최종 요약

이번 작업으로 프로젝트는 다음 상태가 되었다.

- API TPS와 DB commit TPS를 분리해서 설명할 수 있다.
- `202 Accepted` 이후 후단 처리량을 지표로 볼 수 있다.
- DLQ 재처리 정책을 테스트로 방어할 수 있다.
- v3에서 사용하지 않는 v1/v2 레거시 실행 코드가 제거되었다.
- Redis Cluster `{campaignId}` 해시태그의 장점과 한계를 명확히 설명할 수 있다.
- 다음 개선 방향을 대기열, bucket 분산, processing queue 관점으로 말할 수 있다.

결론적으로 이번 작업은 기능을 크게 추가한 작업이라기보다, 기존 v3 구조를 운영 관점에서 더 잘 관측하고, 현재 실행 코드만 남도록 정리한 개선이다.
