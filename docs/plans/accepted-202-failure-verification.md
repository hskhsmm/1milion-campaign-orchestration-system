# 202 접수 이후 장애 검증 계획

> 상태: Bridge 전달 창 보호 코드·단위 테스트와 로컬 Redis 7.2/3노드 Cluster에서 Lua 직접 실행 확인. 실제 앱·Kafka를 연결한 종료·복구 테스트와 실환경 장애 주입은 미실행. 이 문서는 202 전체 안전성 확보를 주장하지 않는다.
> 범위: DLQ·배치 역할을 바꾸기 전에, 현재 `202 Accepted`의 보장 경계와 유실·중복 가능 구간을 확인한다.

## 현재 확인한 경로

`ParticipationController`는 `ParticipationService.participate()`가 반환되면 202를 보낸다. 그 메서드는 Lua의 재고 차감·중복 표시·Redis List `LPUSH`가 완료되면 반환한다. Kafka 발행이나 DB INSERT는 202 전에 기다리지 않는다.

| 경계 | 코드에서 확인한 사실 | 아직 보장하지 못하는 것 |
| --- | --- | --- |
| Redis Lua → 202 | `check-decr-enqueue.lua`가 같은 샤드에서 재고·큐·중복 키를 변경한다. `infra/elasticache.tf`는 3샤드, 샤드당 replica 1개, 자동 failover를 선언한다. | 202 직후 primary 장애 시 새 primary에 해당 쓰기가 반드시 남는다는 보장. 현재 요청 경로에는 복제 확인이나 별도 영속 접수 기록이 없다. |
| Redis Queue → Kafka | `bridge-claim.lua`가 Queue에서 꺼낸 원본을 in-flight ZSET에 원자적으로 보관한다. Kafka 성공 후 전용 실행기에서 해당 claim을 제거하며, 만료된 claim은 Lua가 Queue로 되돌린다. 로컬 Redis Cluster에서 Lua 동작은 확인했다. | 실제 앱·Redis Cluster·Kafka를 통한 종료·복구 통합 검증. 5분 lease 초과나 Redis ACK 실패·실행기 거부에서는 Kafka 중복이 생길 수 있다. |
| Kafka → DB | Consumer는 batch INSERT 후 수동 ack한다. DB 예외 시 행별 재시도하고 일시 실패가 있으면 ack하지 않는다. | 단순히 ack를 생략한 뒤 정상 반환했을 때 언제 재전달되는지, batch 내 성공분과 실패분의 재시도 결과. 별도 검증이 필요하다. |
| 실패 → DLQ | Bridge의 Kafka 전송 실패는 claim을 남겨 재시도하고, Consumer는 DLQ 저장/발행을 호출한다. | Consumer DLQ 저장/발행 실패가 호출자에게 전파되는지, 실제로 내구성 있게 남았는지. 현재 `DlqMessageService` 내부에서는 두 예외를 기록만 하고 삼키며 Kafka 발행 완료도 기다리지 않는다. DLQ의 설계 변경은 다음 작업으로 분리한다. |

정합성 복구 Job은 Redis 재고·큐 크기·활성 상태와 DB 성공/대기 건수를 비교하고 일부 상태를 복원한다. 그러나 Redis Queue와 Kafka 양쪽에서 원본 메시지가 사라진 경우, 현재 코드에서 해당 사용자의 이벤트 본문을 재구성할 근거는 확인되지 않았다. 따라서 **재고 숫자가 맞는 것과 202를 받은 사용자 전원의 DB 반영은 별개**로 검증한다.

Redis `WAIT`를 나중에 검토하더라도 Lua 내부에서는 대기 명령으로 사용할 수 없고, 쓰기를 실행한 연결과 응답값 확인이 중요하다. 또한 `WAIT` 자체도 failover에서 강한 일관성을 보장하지 않는다. 이번 브랜치에서 이를 무작정 202 경로에 추가하지 않는다. [Redis WAIT](https://redis.io/docs/latest/commands/wait/), [Redis 복제](https://redis.io/docs/latest/manual/replication/).

## 작은 실험부터 진행

1. **로컬 단위·Redis 테스트**: Kafka send future 성공 전 claim을 제거하지 않고 실패·ACK 실행 거부 시 유지하는 것을 확인했다. `BridgeQueueServiceTest`는 같은 Redis Cluster 슬롯의 키와 claim 분리를 확인한다. 별도 3노드 Redis Cluster에서 Lua를 직접 실행해 Queue→in-flight 이동, 만료 복구, 원자적 잔량 합을 확인했다. 이것을 앱·Kafka 전체 경로나 실제 장애 복구를 검증한 것으로 해석하지 않는다.
2. **정상 경로 소량 기준선**: 실환경을 사용할 때 새 테스트 캠페인과 합성 userId만 사용한다. 앱 이미지/SHA, Redis·Kafka·DB 상태를 기록하고, 202 수와 최종 DB 신규 건수·중복·재고·Queue·lag를 대조한다. 기준선 없이 장애를 주입하지 않는다.
3. **Bridge 경계 재현**: 전용 격리 환경에서 Queue → in-flight 직후 Kafka 완료 전 종료를 제어한다. 5분 이상 경과한 뒤 같은 합성 userId가 재전송되고 최종 DB에 정확히 1행 남는지 확인한다. 기존 공유 AWS 환경에서 컨테이너를 임의로 죽이지 않는다.
4. **Redis failover 경계**: 3샤드 중 해당 캠페인의 primary/replica, 복제·영속 설정, 대상 환경의 격리 여부를 확인한 뒤 별도 승인된 시험으로 진행한다. ElastiCache failover는 그 샤드의 다른 요청에도 영향을 주므로 소량 캠페인이라는 이유만으로 안전하지 않다.
5. **Consumer 재전달 경계**: DB 실패 시 ack 생략만으로 즉시 재전달된다고 가정하지 않는다. 실행 중 복구·재시작/리밸런스 후 복구를 구분해 DB 건수와 Kafka offset을 확인한다.

실험마다 입력 synthetic ID 집합, 202 수, 테스트 시각, Queue `LLEN`, Kafka lag/offset, DLQ DB·토픽, 최종 `participation_history`의 신규 건수와 중복을 같은 실행 ID에 모은다. 메트릭 합계만으로 개별 202의 도달을 증명하지 않는다. 비밀값이나 실제 사용자 데이터는 증거에 넣지 않는다.

## 이 단계의 종료 조건

- 202 직후, Redis pop 직후, Kafka 수신 후의 장애 경계별로 **확인된 사실 / 코드상 가능성 / 미검증**을 구분한다.
- 작은 정상 실행의 202·재고·큐·Kafka·DB 결과를 보존한다.
- 재현된 유실·중복 또는 판정 불가의 증거를 남기고, DLQ·배치 설계에 넘길 구체적인 요구사항을 정한다.

이 단계에서는 대규모 부하, Redis failover, 앱 강제 종료, DLQ·배치 수정, 200만 건 테스트를 자동 실행하지 않는다. 장애 시험은 환경·영향 범위·복구 방법을 확인한 뒤 별도로 시작한다.

## Bridge 변경 운영 주의

- `bridge:inflight:campaign:{id}`의 크기가 0이고 Queue도 0인지 확인하기 전에는 `env-down`으로 Redis를 삭제하면 안 된다. 현재 `env-down`에는 이를 자동 검증하는 가드가 없다.
- 앱을 롤링 배포하는 동안 구버전 Bridge가 같은 Queue를 읽으면 구버전의 `RPOP` 유실 창이 잠시 남는다. 활성 캠페인이 없는 시점에 배포하고, 롤백 전 in-flight 잔량과 처리 방법을 확인한다.
- 한 캠페인의 in-flight를 10,000개로 제한해 Kafka 장애 중 무제한 선행 pop을 막는다. 5분 lease 만료 후 최대 500개씩 복구하므로 복구 시간은 잔량과 캠페인 수에 좌우된다.
- Kafka 성공 콜백의 Redis ACK는 4개 스레드·대기 2,048건으로 제한한 실행기에서 처리한다. 실행기 포화·종료 시 `bridge.claims.ack.rejected`를 기록하고 claim을 남겨 만료 복구한다. 이 경우 Kafka 중복 발행이 늘 수 있다.
- Bridge의 Kafka 전송 실패는 이제 즉시 DLQ로 이동시키지 않고 Redis claim으로 보관해 재시도한다. 영구적인 잘못된 메시지는 반복될 수 있으므로 별도의 횟수 제한·격리 정책은 DLQ·배치 설계에서 결정해야 한다.
