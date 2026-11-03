# Bridge 작은 batch claim 설계

> 작성: 2026-11-03 / 기준 코드: `c236653`(main) / 브랜치: `perf/bridge-batch-claim` / 구현 커밋: `4804dce`(batch claim), `10629fb`(Redis topology refresh)
> 상태: **구현, 로컬 검증 1~5단계 완료(8장), AWS 비교는 이번 범위에서 제외**. 로컬 결과: [로컬 검증](../reports/2026-11-03-bridge-batch-claim-local.md).
> 상위 계획: [성능 개선 중간 정리](performance-next-steps.md) 5장 2번.

## 1. 가설

drain 구간에도 claim 평균 약 1.4~1.6ms가 **건마다** 발생했다([Bridge 대기 분석](../reports/2026-11-03-bridge-drain-300k.md)). 한 번의 Lua 호출로 여러 건을 Queue→in-flight로 옮기면 Redis 왕복 횟수가 줄어 Bridge 발행률이 오르고, 최대 Queue와 부하 종료 후 완료 시간이 줄어들 것이다.

반증 조건: 적용 후에도 Bridge 발행률이 변하지 않거나, API p95/p99·Redis CPU가 의미 있게 악화되면 이 가설만으로는 개선이 아니다. 병목이 Kafka 확인·ACK·Consumer 쪽이라면 claim만 줄여도 효과가 작다.

## 2. 바꾸는 것과 바꾸지 않는 것

바꾸는 것은 **claim 묶음 크기 하나**다.

```text
현재: 1건 claim → 1건 send, 1건 claim → 1건 send, ...
변경: N건 claim → N건 send, N건 claim → N건 send, ...
```

바꾸지 않는 것: Kafka 성공 후 이벤트별 ACK(`ZREM`), in-flight 상한 10,000, claim lease 5분, reclaim(5초 주기·500건), drain 간격 100ms, 회차 한도 500/1,000/2,000건, Kafka producer·Consumer 설정.

## 3. Lua (`bridge-claim-batch.lua`)

KEYS는 기존과 같은 `queue:campaign:{id}`, `bridge:inflight:campaign:{id}`(같은 슬롯).

1. 입력 검증: N이 1 이상 정수, in-flight 상한이 양의 정수, batch 토큰이 비어 있지 않고 `|`가 없음
2. 키 타입 검증: Queue는 list 또는 없음, in-flight는 zset 또는 없음
3. `k = min(N, 상한 - ZCARD, LLEN)`. `k ≤ 0`이면 빈 목록
4. `LRANGE queue -k -1`로 꼬리 k건 조회
5. 결과를 뒤집어 오래된 순으로 claim(`<batch토큰>:<0..k-1>|<원본>`) 생성
6. **단일 `ZADD`** 로 k건을 in-flight에 등록 ← 첫 쓰기
7. `LTRIM queue 0 -(k+1)`로 꼬리 k건 제거
8. 오래된 순 claim 목록 반환

모든 검증은 첫 쓰기 전에 끝난다. Redis Lua는 중간 실패 시 앞선 쓰기를 되돌리지 않으므로, 이벤트별 `ZADD`를 반복하면 일부만 in-flight에 남고 원본도 Queue에 남는 상태가 생길 수 있다. 단일 `ZADD` 명령은 k건이 함께 성공하거나 함께 실패한다. `ZADD`가 실패하면(OOM 등) 원본은 Queue에 그대로 남는다. OOM 시 Queue에서 꺼내지 못하는 기존 동작은 바꾸지 않는다(Redis OOM 대책은 별도 범위).

순서: 접수는 `LPUSH`, 꺼내기는 꼬리부터다. 입력이 1→5이고 N=3이면 첫 batch는 1,2,3, 다음은 4,5다. 보장하는 것은 **한 batch 안의 claim·send 호출 순서**뿐이며, 여러 앱 인스턴스·비동기 Kafka를 포함한 전체 순서가 아니다.

claim ID: batch마다 UUID 1개 + 인덱스. 같은 원본이 다시 claim돼도 이전 claim과 다르므로 늦은 ACK가 새 claim을 지우지 못한다. 기존 reclaim은 첫 `|` 뒤를 원본으로 복원하므로 원본 JSON에 `|`가 있어도 된다.

## 4. Java

- `BridgeQueueService.claimBatch(campaignId, requested)`: 1~50 밖이면 Redis 호출 없이 거부. 요청보다 많이 오거나 형식이 틀리면 예외.
- `ParticipationBridge`: 설정 `bridge.claim-batch-size`(기본 1, 허용 1~50). 범위 밖이거나 숫자가 아니면 기동 실패. **1이면 기존 `bridge-claim.lua` 단건 경로를 그대로 사용**하므로 같은 이미지에서 설정만 1로 되돌릴 수 있다.
- 반복문은 호출 수가 아니라 **이벤트 수**로 회차 한도를 센다. 한도 500, N=30이면 30×16회 + 20 = 500건이다(500×30이 아니다). 한도는 앱 인스턴스별·캠페인별 drain 1회 기준이다.
- 빈 결과: 기존과 같이 캠페인이 비활성이면 비활성화를 시도하고 회차 종료. 요청보다 적게 오면(Queue 소진·in-flight 상한) 회차 종료, 비활성화 판단은 다음 회차의 빈 결과에서 한다.

## 5. 실패 시 예상 동작

| 상황 | 결과 | 기존과 차이 |
| --- | --- | --- |
| Kafka 발행 실패(비동기·동기 예외) | 해당 claim만 in-flight에 남음 → 5분 후 reclaim → 재발행 | 같음 |
| 앱이 claim 후 종료 | batch 전체가 in-flight에 남음 → 5분 후 복구 | 한 번에 최대 N건 지연 |
| Redis는 처리했지만 앱이 응답을 못 받음(타임아웃) | Queue에서 빠지고 in-flight에 있음, 앱은 발행 못 함 → 5분 후 reclaim | 유실 없음, 최대 N건이 최대 5분 지연 |
| 늦은 ACK | 고유 claim ID라 새 claim을 못 지움 | 같음 |
| 키 타입 오류·입력 오류 | 쓰기 전에 오류, Queue·in-flight 불변 | 단건 Lua에는 없던 사전 검증 |

재시도로 Kafka 중복은 발생할 수 있으며 DB `(campaign_id, user_id)` 고유 제약에 의존하는 점은 기존과 같다.

## 6. 지표

batch에서는 호출 수와 이벤트 수가 달라지므로 분리했다(단건 경로도 같은 지표를 기록).

| 지표 | 의미 |
| --- | --- |
| `bridge.claim.calls{result=claimed\|empty}` | claim Lua 호출 횟수. 이전 이름 `bridge.claim.results{claimed\|not_claimed}` |
| `bridge.claim.events` | 실제 claim한 이벤트 수 |
| `bridge.claim.batch.size` | 1건 이상 선점한 호출당 건수 분포 |
| `bridge.redis.duration{operation=claim}` | 호출당 클라이언트 시간(변경 없음) |
| `bridge.claim.ack.results{result=removed\|not_found\|error\|rejected}` | ACK 결과. removed=정상(ZREM 1), not_found=ZREM 0(reclaim이 먼저 실행됐거나 예상 밖 중복 삭제), error=Redis 예외·결과 없음, rejected=Kafka 성공 후 ACK 실행기 포화로 ACK를 시도하지 못함. 기존 `bridge.claims.ack.rejected`도 호환용으로 유지 |

이벤트당 claim 비용 = claim timer 합계 ÷ `bridge.claim.events`. 조회식은 [조회 목록](../../stress-test/bridge-observation-queries.tsv), Grafana "Bridge claim 호출·이벤트" 패널.

## 7. AWS 비교 계획 (사용자 승인 후)

순서: **N=1 기준선 → N=10 → N=50 → N=1 재확인**. 마지막 N=1로 JVM 워밍업·실행 이력 차이를 가늠한다.

각 시험 전 맞출 조건: 같은 Git SHA·Docker 이미지, 같은 앱 수·사양(사전 증설, 자동 증설 배제), 같은 Redis·Kafka·DB 구성, 30만 shared-iterations·500 VU, 같은 JVM 워밍업 절차, 새 캠페인, 이전 Queue/in-flight/lag 0 확인. 설정 변경은 `bridge.claim-batch-size`만, 변경 후 앱 재기동 방식도 기록한다. 각 조건 1회면 결과는 탐색 수준이며 개선율로 확정하지 않는다.

성능 지표: 요청 RPS, 202 TPS, API p95/p99, claim 호출 시간·이벤트당 시간, Bridge Kafka 발행 TPS(유입 중·drain 분리), Consumer 처리 TPS, DB INSERT TPS, 최대 Queue·in-flight, Kafka lag, Redis CPU·SLOWLOG, 부하 종료 후 최종 완료 시간.

정합성 지표: 요청 수, 202 수, claim 이벤트 수, Kafka 발행 성공 수, ACK removed·not_found·error·rejected 수, reclaim 수, Consumer 처리 수, DB 신규 INSERT 수, 중복 사용자·순번 수, DLQ 수, Redis·Kafka 발행 오류 수, 최종 Queue/in-flight/lag, k6 `dropped_iterations`, 예상 밖 5xx·네트워크 오류.

장애를 주입하지 않은 정상 시험의 기대값:

```text
요청 = 202 = claim 이벤트 = Kafka 발행 = DB 신규 INSERT
ACK removed = Kafka 발행 성공, ACK not_found·error·rejected = 0, reclaim = 0, Redis/Kafka 오류 = 0
중복 = 0, DLQ = 0, 최종 Queue/in-flight/lag = 0, dropped_iterations = 0
```

DB 건수만 맞는 것으로 통과시키지 않는다. DB 고유 제약이 Kafka 중복 발행을 숨길 수 있으므로 claim 이벤트·발행·reclaim 수까지 대조한다.

## 8. 검증 순서

한 번에 섞지 않는다. 1~4는 로컬 신뢰성, 5는 별도 장애 검증, 6은 성능 검증이며 5와 6을 같은 실행에서 섞지 않는다.

| 순서 | 검증 | 상태 |
| --- | --- | --- |
| 1 | ACK 결과 메트릭(removed/not_found/error/rejected) | 완료(단위·실제 Redis) |
| 2 | Redis 처리 후 응답 유실 | 완료(앱 쪽 예외로 재현) |
| 3 | 여러 Bridge 동시 claim A·B·C 각 20회 | 완료 |
| 4 | 앱 강제 종료(Kafka 중단 → claim → kill → 복구 → DB 대조) | 완료(100건 PASS) |
| 5 | 로컬 Redis Cluster 3 primary + replica, failover A·B·C | refresh 적용 전 B 가용성 FAIL(앱 재시작 필요) → Redis timeout·topology refresh 적용 후 다중 샤드 PASS, A·B·C 자동 복구·정합성 PASS, failover 구간 가용성 저하(B `500` 1건, C `500` 31건) |
| 6 | AWS N=1 → 10 → 50 → 1 | 미실행 |

결과: [로컬 검증](../reports/2026-11-03-bridge-batch-claim-local.md).

## 9. 남은 위험

- N이 크면 같은 샤드의 접수 Lua 대기가 늘 수 있다. 로컬 서버 실행 시간은 N=50에서 호출당 약 0.15ms였지만 ElastiCache 수치가 아니다 → AWS에서 API p99·SLOWLOG 동시 확인.
- 앱 종료·응답 유실 시 최대 N건이 lease 5분 동안 함께 지연된다. 총량은 in-flight 상한 안이다.
- claim이 빨라지면 ACK(`ZREM`)·Kafka 확인·Consumer가 다음 병목이 될 수 있다. 다음 단계에서 따로 본다.
