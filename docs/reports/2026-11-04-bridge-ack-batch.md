# Bridge ACK 묶음 처리와 역압 (2026-11-04)

> 브랜치 `perf/bridge-ack-batch`(main `8ba2ef9` 기준). 운영 N=10에서 확인한 ACK 병목의 수정과 로컬 검증 기록이다. **운영 재시험(N=10)은 아직 하지 않았다.**

## 1. 운영에서 확인한 문제

PR #120(batch claim)·#121(N을 SSM으로 주입) 머지 후, 운영(앱 t3.small 2대 고정, 30만 건·500 VU integrity)에서 `bridge.claim-batch-size`를 바꿔 비교했다.

| 항목 | N=1 (캠페인 115) | N=10 (캠페인 117) |
| --- | ---: | ---: |
| 요청 / 202 / DB SUCCESS | 300,000 / 300,000 / 300,000 | 300,000 / 300,000 / 300,000 |
| 접수 중 Bridge 발행 | 571/s | 1,306/s |
| ACK 거부 (`ack.results{rejected}`) / reclaim | 0 / 0 | **7,157 / 7,157** |
| Kafka 발행 합계 | 300,000 | **307,157** (중복 7,157) |
| in-flight 최대 | 66 | **9,987** (상한 10,000) |
| 접수 끝 → Kafka 발행 완료 | 85초 | **335초** |
| drain 구간 앱 CPU | 0.43 | **0.02** |

같은 Prometheus 쿼리·같은 구간 정의로 집계했다. 각 조건 1회.

원인:

1. Kafka 성공 콜백마다 `bridgeAckExecutor`(`ThreadPoolExecutor(4, 4)`, `ArrayBlockingQueue(2048)`, `AbortPolicy`)에 단건 `ZREM` 작업을 넣었다.
2. 부하 중 ACK 1건은 평균 약 1.9ms였다. 그러면 ACK 처리량 상한은 약 4 ÷ 1.9ms ≈ **앱당 2,100건/s**다.
3. N=10에서 발행이 순간 약 5,900건/s(앱 2대 합)까지 올라가 큐가 넘쳤다. 로그: `RejectedExecutionException … pool size = 4, active threads = 4, queued tasks = 2048`.
4. 거부된 claim은 in-flight에 남았다. 이것이 상한 10,000에 닿자 claim이 멈췄고, lease(5분) 만료를 기다린 뒤 reclaim → 재발행됐다. Consumer에서는 중복 INSERT 데드락 → 배치 재시도가 났다(`Deadlock found`, 51건).
5. DB 고유 제약 때문에 최종 건수는 맞았다. 유실은 없었다. 하네스 판정은 PASS였지만 계획서 기대값(ACK rejected·reclaim 0, 발행 = 요청)을 만족하지 못했다.

이는 [batch claim 계획](../plans/bridge-batch-claim.md) 9장의 위험 "claim이 빨라지면 ACK가 다음 병목"이다.

## 2. 변경

| 파일 | 내용 |
| --- | --- |
| `BridgeAckDispatcher` (신규) | Kafka 완료 콜백은 claim을 대기열(`ConcurrentLinkedQueue`)에 넣기만 한다(블로킹 없음). 실행기에는 대기열을 비우는 drainer를 최대 4개만 둔다. drainer는 최대 100건씩 꺼내 캠페인별로 묶고 `ZREM key m1 … mK` 한 번으로 지운다. 1건이면 기존 `acknowledge(claim)`을 쓴다. 대기 상한 20,000(넘치면 기존과 같이 rejected로 기록하고 reclaim에 맡김) |
| `BridgeQueueService.acknowledgeBatch` | 같은 캠페인(같은 슬롯) claim의 묶음 `ZREM`. 삭제 수 = removed, 나머지 = not_found, 예외 = 건수만큼 error. 다른 캠페인이 섞이면 거부 |
| `ParticipationBridge` | ACK를 디스패처로 넘긴다. **역압**: 대기 ACK가 5,000건 이상이면 그 회차 claim을 쉰다. 이벤트는 Queue에 남는다 |
| 메트릭 | `bridge.ack.pending`(게이지), `bridge.ack.batch.size`(ACK 호출당 건수), `bridge.claim.skipped{reason=ack_backlog}` |

바뀌지 않은 것: claim·reclaim Lua, lease 5분, in-flight 상한 10,000, Kafka 발행 경로, `bridgeAckExecutor` 구성, N 기본값 1.

### 정합성

- claim은 `ZREM`이 실행되기 전까지 Redis in-flight에 남는다. 디스패처 대기열이 앱 종료로 사라지면, 기존 실행기 큐가 사라질 때처럼 lease 만료 후 reclaim된다. **새 유실 경로는 없다.**
- `ZREM`은 멱등이다. 묶음 실패도 기존 단건 실패와 같은 복구 경로(reclaim → 재발행 → Consumer·DB 멱등 처리)를 탄다.
- 역압은 claim을 하지 않을 뿐이다. 이벤트는 Queue에 그대로 있다.
- 역압이 있으므로 대기 ACK는 대략 "5,000 + 한 회차 최대 2,000"에서 멈춘다. 20,000 상한 거부는 여러 캠페인이 겹치는 경우를 위한 안전망이다.

### 지표 해석 변화

- `bridge.redis.duration{operation=ack}` count는 이제 **Redis 호출 수**다. 처리한 이벤트 수는 `bridge.claim.ack.results` 합계로 본다.
- 묶음에서는 어느 member가 not_found였는지 구분하지 않는다(건수만 정확).
- `ack.results{error}`는 "결과를 모름"이다. 로컬 시험에서 Redis를 멈추자 묶음 `ZREM`이 timeout(2초)으로 error가 됐다. 그러나 이미 보낸 명령이 Redis가 깨어난 뒤 실행돼 in-flight는 0이었다. 정합성 문제는 아니지만 error 수를 "남아 있는 claim 수"로 읽으면 안 된다.

## 3. 검증

### 단위·실제 Redis 시험 (깨끗한 worktree, 로컬 Redis 7 `BRIDGE_TEST_REDIS_PORT`)

전체 191건 통과(실패 0, 다른 환경 조건으로 건너뜀 11).

| 시험 | 확인 |
| --- | --- |
| `BridgeAckDispatcherTest` 7건 | 캠페인별 분리·100건 상한, 1건은 단건 경로, 대기 상한 거부, drainer 수 상한, ACK 실패 후 다음 묶음 계속, 종료 후 새 제출 재스케줄, drainer 거부 시 claim 유지 |
| `ParticipationBridgeAckBackpressureTest` | 대기 5,000에서 claim 쉼, ACK 처리 후 재개 |
| `BridgeBatchClaimRedisTest` +2건 | 30건 묶음 ACK 중 reclaim이 먼저 이긴 3건 = not_found, 새 claim은 지우지 않음, ACK 호출 2회. 캠페인 혼합 거부 |
| 기존 시험 2건 수정 | "실행기 거부 → rejected 카운터"가 "drainer 거부 → claim이 대기에 남고 ACK 안 함"으로 바뀜(claim 유지 보장은 같음) |

### 로컬 Docker (`stress-test/local-fault/bridge-batch-claim/compose-kill.yml`, Redis Cluster 3 primary, N=50)

| 시험 | 결과 |
| --- | --- |
| (a) 5,000건 병렬 50 | 202 = claim = 발행 = ACK removed = DB = 5,000, 고유 사용자·순번 5,000, Kafka offset +5,000. ACK 거부·reclaim·not_found·error·역압 0. **ACK Redis 호출 138회**(평균 36건/회, 이전 방식이면 5,000회) |
| (b) 부하 2초 뒤 앱 `SIGKILL` → 재시작 | 접수 1,071(Redis 재고 감소) = DB 1,071 = Kafka 1,071, 중복 0, 최종 Queue·in-flight 0. 202 응답 1,070(1건은 접수 후 응답만 끊김) |
| (c) 부하 중 해당 슬롯 Redis primary `docker pause` → 앱 `SIGKILL` → unpause → 재시작 | ACK 묶음 1회(74건) timeout → error 74. 접수 905 = DB 905 = Kafka 905, 중복 0, 최종 Queue·in-flight 0 |

- (b)·(c) 모두 kill 시점의 in-flight가 0이었다. "Kafka 성공 후 ACK가 디스패처 대기열에 있는 순간의 kill"은 로컬 처리 속도가 빨라 만들지 못했다. 이 경우의 보장은 Redis 상태(in-flight 유지 → reclaim)가 기존과 같다는 것과 단위 시험에 근거한다.
- (c)에서 lag가 0인데 DB가 875로 보였다가 약 30초 뒤 905가 됐다. 관측 도구의 lag가 DB 반영보다 먼저 0이 되므로 최종 대조는 값이 안정된 뒤 한다.
- 로컬은 운영보다 부하가 낮다. 처리량 개선 수치로 쓰지 않는다.

## 4. 미검증과 다음

- **운영 N=10 재시험**: 기대값은 ACK rejected 0, reclaim 0, 발행 = 300,000, 접수 끝 → 발행 완료가 N=1(85초)보다 짧은 것. 이후 N=50.
- ACK 대기 중 kill의 직접 재현.
- 묶음 `ZREM` 100건이 같은 샤드의 접수 Lua 지연(API p99)에 주는 영향 — 운영에서 SLOWLOG·p99와 함께 확인.
- 하네스 판정이 ACK rejected·reclaim을 보지 않아 N=10 문제를 PASS로 냈다. 판정 항목 추가는 별도 과제.
