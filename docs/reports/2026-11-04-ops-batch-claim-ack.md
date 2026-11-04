# 2026-11-04 작업 종합: 운영 batch claim 비교, ACK 병목 수정, failover·scale-out 확인

> 계정 631124976154, ap-northeast-2. 앱 t3.small, Kafka 브로커 3대(t3.small), ElastiCache cache.t3.micro 3샤드+replica(자동 failover), RDS db.t3.micro 8.4.10.
> 관련 PR: #120(batch claim, Lettuce topology refresh·`timeout: 2s`, 11-03 작업), #121(N을 SSM으로 주입), #122(묶음 ACK·역압).
> 판정·증거는 운영 PC의 증거 수집 스크립트로 모았다(main에 없음, 증거 원본은 Git 제외). 각 조건 1회이며 개선율을 확정하지 않는다.
> 코드 변경 상세와 로컬 시험 목록: [ACK 묶음 처리](2026-11-04-bridge-ack-batch.md) (부록).

## 요약

| 질문 | 답 |
| --- | --- |
| PR #120은 운영에서 정상인가 | 예. N=1에서 202 = 발행 = DB = 300,000, Redis timeout 0 |
| N=10으로 빨라졌나 | 처음엔 아니다. **ACK 병목**으로 중복 발행 7,157건, 완료가 455초로 오히려 느려졌다 |
| 고쳤나 | 묶음 ACK(PR #122) 후 N=10에서 중복 0, 접수 시작 → 발행 완료 **195초(N=1) → 125초** |
| 대가는 | 서버 p99 **407 → 659ms**. Redis가 아니라 앱 CPU를 API와 Bridge가 나눠 쓴 결과로 추정 |
| 장애에 버티나 | ElastiCache failover: 앱 재시작 없이 **약 22초** 만에 회복, 접수 = DB. scale-out: 정합성 유지, 합류 직후 약 1분 처리율 저하 |
| 다음은 | **API / worker 역할 분리**([다음 작업](../plans/performance-next-steps.md) 5-1장) |

## 1. 배경과 실행 조건

11-03에 batch claim(`bridge.claim-batch-size` 1~50)과 Redis failover 대응(Lettuce refresh·timeout)을 로컬에서 검증하고 PR #120으로 머지했다. 오늘은 운영에서 N=1 → 10 → 50 → 1을 비교하는 것이 목표였다.

| 항목 | 값 |
| --- | --- |
| 부하 | k6 shared-iterations, 500 VU, 기본 30만 건(scale-out은 120만 건), 캠페인마다 새로 생성 |
| 앱 대수 | 2대 고정(시험 동안 ASG max 3 → 2). scale-out 시험만 max 3 |
| N 변경 | 운영에서 N을 바꿀 경로가 없어 PR #121로 SSM `/batch-kafka/prod/BRIDGE_CLAIM_BATCH_SIZE`를 배포 env에 연결했다. 값 변경 후 CodeDeploy 재배포 → 매 회차가 "재배포 직후 첫 대규모 부하" |
| 비교 방법 | terraform-mcp Prometheus(보존 15일)에서 모든 캠페인을 **같은 쿼리·같은 구간 정의**로 다시 집계. 접수 구간 = 202 rate(30s) > 100/s, 완료 = `bridge.messages.published{campaignId}` 합 = 요청 수(Kafka 발행 기준이며 DB 반영 완료가 아님) |
| 정합성 확인 | k6 202, 상태 API, DB SUCCESS, sequence·user 중복, 최종 Queue `LLEN`·in-flight `ZCARD`·lag, 앱 actuator의 claim·발행·ACK·reclaim 카운터 |

## 2. 전체 결과 표 (30만 건, 앱 2대)

| 캠페인 | 116 | 115 | 117 | 118 |
| --- | --- | --- | --- | --- |
| 앱 | `5b110eb`(PR #120 전) | `f7104a4` | `8ba2ef9` | `6f3fa37` |
| N / ACK 방식 | 1 / 건별 | 1 / 건별 | 10 / 건별 | 10 / **묶음** |
| 202 = DB, 중복 | 300,000, 0 | 300,000, 0 | 300,000, 0 | 300,000, 0 |
| Kafka 발행 | 300,000 | 300,000 | **307,157** | 300,000 |
| ACK 거부 / reclaim | 0 / 0 | 0 / 0 | **7,157 / 7,157** | 0 / 0 |
| in-flight 최대 | 46 | 66 | **9,987** | 299 |
| 평균 202 | 2,857/s | 2,727/s | 2,560/s | 2,500/s |
| 서버 p95 / p99 (접수 +60초~끝) | 272 / 490 ms | 264 / 407 ms | 324 / 548 ms | **382 / 659 ms** |
| 접수 중 Bridge 발행 | 335/s | 571/s | 1,306/s | **2,050/s** |
| 접수 끝 → 발행 완료 | 215초 | 85초 | 335초 | **5초** |
| 접수 시작 → 발행 완료 | 320초 | 195초 | 455초 | **125초** |
| claim Redis 호출 평균 (접수 / drain) | 8.3 / 1.45 ms | 5.7 / 0.64 ms | 17.5 / 1.54 ms | 11.8 ms / — |
| 앱 CPU (접수) | 0.92 | 0.90 | 0.99 | 0.96 |

참고: PR #120 전 코드로 전날 같은 조건에서 한 107(`5b110eb`)은 116과 거의 같았다(발행 완료 230초, drain 발행 1,166/s).

## 3. 운영 기준선과 적용 전 A/B (115, 116)

- **115 (N=1, `f7104a4`)**: 정합성 정상. claim 이벤트 = 발행 = ACK removed = Consumer = DB = 300,000. 새로 넣은 Redis `timeout: 2s`로 인한 timeout·연결 실패 로그는 0이었다.
- **N=1인데 drain이 빨라졌다.** 전날 107 대비 발행 완료가 230초에서 85초로 줄었다. 그래서 같은 env에 PR #120 전 이미지를 다시 배포해 **116**을 돌렸다. 116 ≈ 107이므로 env·날짜·Redis 상태 차이로는 설명되지 않는다.
- N=1 경로의 Java 코드는 같다(같은 단건 Lua·반복문). 남은 동작 차이는 `application.yml`의 Lettuce `timeout: 2s`와 cluster topology refresh다. 개선은 클라이언트 측 claim 호출 시간(1.45 → 0.64ms)에서 나타났다. **원인은 추정**이며 메커니즘은 확인하지 않았다.
- 제외한 후보: ElastiCache CPU 크레딧(10~18 잔여, 엔진 CPU 최대 약 25%), 앱 EC2 크레딧(`unlimited`, surplus 0).

## 4. N=10에서 발견한 ACK 병목 (117)

DB 건수만 보는 기존 판정은 PASS였다. 그러나 Bridge 카운터는 다음을 보여 줬다.

1. N=10으로 claim·발행이 빨라졌다(접수 중 571 → 1,306/s).
2. Kafka 성공 후 ACK(`ZREM`)를 넘기는 실행기(`ThreadPoolExecutor(4, 4)`, 큐 2,048, `AbortPolicy`)가 포화됐다. **ACK 7,157건이 거부**됐다(`RejectedExecutionException … queued tasks = 2048`).
3. 거부된 claim이 in-flight에 남아 상한 10,000 근처(9,987)에 닿았고 claim이 멈췄다. drain 구간 앱 CPU 0.02, 회차 9ms로, 일을 하지 않고 lease(5분) 만료를 기다렸다.
4. 만료 후 reclaim → **중복 발행 7,157건** → Consumer 중복 INSERT 데드락(51건) → 배치 재시도. DB 고유 제약으로 최종 건수는 맞았다.

원인 계산: 부하 중 ACK 1건 ≈ 1.9ms였다. 그래서 4스레드의 처리 상한이 앱당 약 2,100건/s다. N=10에서 발행은 순간 약 5,900건/s(2대 합)였다. claim은 묶었는데 ACK는 건별 Redis 왕복이라 생긴 구조 문제다. [batch claim 계획](../plans/bridge-batch-claim.md) 9장에 위험으로 적어 둔 "claim이 빨라지면 ACK가 다음 병목"이 그대로 일어났다.

## 5. 수정: 묶음 ACK와 역압 (PR #122)

스레드·큐 크기를 늘리는 방법은 상한만 올릴 뿐이라 택하지 않았다. ACK도 claim처럼 묶었다.

| 변경 | 내용 |
| --- | --- |
| `BridgeAckDispatcher` | Kafka 완료 콜백은 claim을 메모리 대기열에 넣기만 한다(블로킹 없음). drainer(최대 4)가 최대 100건씩 꺼내 **캠페인별 `ZREM` 한 번**으로 지운다. 1건이면 기존 단건 ACK. 대기 상한 20,000 |
| `BridgeQueueService.acknowledgeBatch` | 같은 슬롯의 묶음 `ZREM`. removed / not_found / error를 건수로 기록 |
| 역압 | 대기 ACK가 5,000건 이상이면 그 회차 claim을 쉰다. 이벤트는 Queue에 남는다 |
| 메트릭 | `bridge.ack.pending`, `bridge.ack.batch.size`, `bridge.claim.skipped{reason=ack_backlog}` |

정합성: claim은 `ZREM`이 실행될 때까지 Redis in-flight에 남는다. 대기열이 앱 종료로 사라져도 기존처럼 lease 만료 후 reclaim된다. 새 유실 경로는 없다.

수치(100, 5,000, 20,000, drainer 4)는 측정으로 정한 값이 아니다.

## 6. 로컬 검증

- 전체 시험 191건 통과(로컬 Redis 7 포함, 다른 환경 조건으로 건너뜀 11). 신규: Dispatcher 7건, 역압 1건, 실제 Redis 묶음 ACK 2건(reclaim이 먼저 이긴 claim = not_found, 새 claim은 지우지 않음).
- 로컬 Docker(Redis Cluster 3 primary, N=50)

| 시험 | 결과 |
| --- | --- |
| 5,000건 병렬 50 | 202 = 발행 = ACK removed = DB = 5,000, 거부·reclaim 0. **ACK Redis 호출 138회**(이전 방식이면 5,000회) |
| 부하 중 앱 SIGKILL → 재시작 | 접수 = DB = Kafka 1,071, 중복 0 |
| 부하 중 Redis primary pause + 앱 SIGKILL | 접수 = DB = Kafka 905, 중복 0 |

- 확인하지 못한 것: "Kafka 성공 후 ACK가 대기열에 있는 순간의 kill"(로컬 처리가 빨라 재현 못 함).
- 관찰: 묶음 `ZREM`이 timeout으로 error가 돼도, 보낸 명령이 Redis가 깨어난 뒤 실행돼 in-flight는 0이었다. `ack.results{error}`는 "결과 모름"으로 읽는다.

## 7. 운영 재시험 (118, N=10 + 묶음 ACK)

- **ACK 병목 해소**: 발행 = ACK removed = 300,000, 거부·reclaim·not_found·error 0, 역압 발동 0. ACK Redis 호출 39,434회(평균 6.4~9.5건/회). in-flight 최대 299.
- **전체 완료 단축**: Bridge가 접수를 거의 실시간으로 따라가 부하 종료 후 5초에 발행이 끝났다(N=1 85초). 접수 시작 → 발행 완료 195 → 125초.
- **API 지연 증가**: 서버 p99 407 → 659ms, k6 처리율 2,926 → 2,333 iter/s(닫힌 모델이라 지연과 함께 감소).
  - 캠페인 샤드 Redis 엔진 CPU는 1분 최대 21%(115는 25%)라 Redis 병목이 아니다.
  - Bridge 작업(직렬화·Kafka send·콜백)이 부하 종료 후가 아니라 접수와 동시에 같은 앱 CPU(0.9 이상)를 쓴 결과로 **추정**한다. 총 작업량은 같고 시점이 앞당겨졌다.
- **N=50은 보류**: Bridge 쪽 여유는 충분하고 앱 CPU가 이미 한계라, 이득은 작고 API 지연만 커질 가능성이 높다.

## 8. ElastiCache failover (119, `6f3fa37` N=10)

- 30만 건 부하 중 k6 시작 40초 뒤 `aws elasticache test-failover --node-group-id 0002`를 실행했다(캠페인 슬롯 9035).
- ElastiCache 이벤트: 21:16:25 호출 → 21:17:10 replica `0002-002` 승격 완료 → 21:19:24 옛 primary 복구.

| 항목 | 값 |
| --- | --- |
| 500 응답 | 5,500 / 300,000 (1.8%) |
| 실제 오류 구간(앱 로그) | **21:16:27 ~ 21:16:49, 약 22초**. 두 대 같음. ElastiCache "완료" 이벤트보다 약 20초 먼저 회복 |
| 앱 재시작 | **0**. Lettuce 재연결·topology refresh WARN만 남음 |
| 접수 / DB / 중복 | 294,500 / 294,500 / 0, 남은 재고 5,500 = 미접수 수 |
| 발행 / reclaim | 294,530 / 30 (failover 창에 걸린 ACK. DB에서 걸러짐) |
| 최종 Queue·in-flight·lag | 0 / 0 / 0 |

- [11-03 로컬 검증](2026-11-03-bridge-batch-claim-local.md) 6장의 topology refresh가 **운영 ElastiCache에서도 동작함**을 확인했다(1회). 수정 전 로컬에서는 앱 재시작 전까지 해당 슬롯이 정지했다.
- failover 구간 요청 실패(약 22초)는 남는다. 이 길이는 2~3초 재시도로 덮을 수 없다. 재시도 설계는 이 수치를 근거로 다시 정한다(로컬 보고서 9장).

## 9. scale-out (122, `6f3fa37` N=10, 120만 건)

- 앱 2대에서 시작했다. k6 시작 22:49:52 → 22:49:53 `set-desired-capacity 3` → 새 인스턴스(CodeDeploy로 `6f3fa37`·N=10 수신) → 22:51:59 ALB healthy.

| 항목 | 값 |
| --- | --- |
| 202 / DB / 중복 / 5xx·timeout | 1,200,000 / 1,200,000 / 0 / 0 |
| 발행 / reclaim / ACK 거부 | 1,200,000 / 0 / 0 (새 인스턴스 발행 63,999) |
| 부하 종료 → Queue·in-flight·lag 0 | 11초 |
| 2대 (합류 전) | 202 약 4,450/s, 서버 p99 약 280ms |
| **합류 직후 약 1분** | 202 약 2,000~2,800/s(최저 1,992), p99 870~1,005ms |
| 3대 (회복 후) | 202 4,800~5,300/s, p99 340~380ms |

- N=10·묶음 ACK에서 인스턴스가 늘어도 전달 정합성이 유지된다.
- 합류 직후 처리율 저하·p99 상승은 [앱 2대·3대 비교](2026-11-03-app-2-vs-3-300k.md)·[재시험](2026-11-03-app-warm-repeat-300k.md)에서 본 신규 인스턴스 현상과 같다(ALB 즉시 투입, JVM 워밍업 전). 이전 150만 실행에서도 scale-out 직후 같은 징후가 있었다. batch claim·묶음 ACK와 무관하다. 이번에는 timeout이 0이었다.
- 선행 실행 두 건은 무효로 처리했다.
  - 120: 90만 건, 사용자 요청으로 중단. 접수 666,503 = DB.
  - 121: 중단 직후 늦게 발동한 step 알람(CPU ≥ 80% 2분)으로 이미 3대인 상태에서 시작해 중단. 접수 261,818 = DB. 세 번째 인스턴스를 지정 종료하고 2대에서 다시 했다.

## 10. 판정 보강

DB 고유 제약은 Kafka 중복 발행을 숨긴다(117이 그 예다). 그래서 정상 integrity 시험의 기대값에 **발행 수 = 202, reclaim 0, ACK 거부 0**을 추가했다(운영 PC 판정 스크립트, 판정기 시험 47건 통과). 이 기준으로 다시 판정하면 117은 FAIL이고 115·116·118은 PASS다.

## 11. 미검증·남은 위험과 다음 작업

미검증:

- Lettuce 설정이 N=1 drain 개선의 원인인지(refresh만 끈 실행)
- N=50, N=10 + 150만·200만, 다중 캠페인 동시 진행
- ACK가 메모리 대기열에 있는 순간의 앱 종료(직접 재현 못 함)

남은 위험:

| 위험 | 일어날 조건 | 영향 |
| --- | --- | --- |
| API와 Bridge·Consumer의 CPU 공유 | 부하 중 Bridge가 빨라질 때 | API p99 증가(407 → 659ms), 대규모에서 timeout 가능 |
| 정상 종료 시 메모리 대기 ACK 유실 | 캠페인 중 배포·scale-in | 유실 없음. 중복 발행 + 5분 지연(최대 앱당 약 5,000~7,000건) |
| 역압이 앱 전체 기준 | 다중 캠페인 + 한 샤드 지연 | 정상 샤드 캠페인의 DB 반영도 일시 정지(유실 없음) |
| 차가운 JVM 합류 | scale-out | 약 1분 처리율 최대 −55%, p99 약 1초 |
| 후단 Consumer·DB | Bridge가 빨라진 뒤 | 접수 중 lag 52 → 184. 전체 완료가 DB 속도로 묶임 |
| failover 구간 요청 실패 | Redis primary 장애 | 약 22초 해당 슬롯 500 |

운영 기본 N: 지금 운영은 N=10이다. API 꼬리 지연과 전체 완료 시간 사이의 선택이며, 역할 분리 A/B 결과로 정한다.

다음 작업 순서는 [성능 개선 중간 정리](../plans/performance-next-steps.md) 5-1장에 있다. 첫 작업은 **API / worker 역할 분리**이고, 정상 종료 처리와 역압 단위는 worker 설계에서 함께 정한다.

## 부록: 운영 상태 (2026-11-04 23시 기준)

- 앱 `6f3fa37` 3대(scale-out 시험 후 유지, scale-in 비활성), SSM `BRIDGE_CLAIM_BATCH_SIZE=10`, ASG min 2 / desired 3 / max 3.
- 캠페인 115~122 생성. 119~122는 재고가 남아 OPEN·active Set에 남아 있다. 데이터는 지우지 않았다.
- Redis node group 0002의 primary는 failover 결과 `0002-002`다.
