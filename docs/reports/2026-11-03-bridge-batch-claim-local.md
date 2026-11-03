# 2026-11-03 Bridge batch claim 로컬 검증

> 대상 코드: 브랜치 `perf/bridge-batch-claim`, `c236653`(main) 기반. batch claim 커밋 `4804dce`, Redis topology refresh 커밋 `10629fb`. 시험 시점에는 미커밋 작업 트리였고, 커밋한 `src` 내용은 시험한 작업 트리와 같다(커밋 후 `git diff 10629fb -- app/campaign-core/src` 변경 없음).
> 시험 이미지(로컬 태그 `bbc-kill-app:local`, `BRIDGE_CLAIM_BATCH_SIZE=50`): 5장·6-1~6-3은 `d08e56de7359`(2026-11-03 22:08:44 KST 빌드, `4804dce`의 코드, refresh 적용 전, `compose-kill.yml`·`compose-failover.yml`). 6-4는 `1be8052d7549`(23:53:20 빌드, `10629fb`의 코드, `compose-failover.yml`). 두 이미지 모두 해당 시점 `src/main` 마지막 수정보다 뒤에 빌드했고, 이후 `src/main`은 바꾸지 않았다.
> 같은 브랜치에 성능 변경(batch claim)과 신뢰성 변경(Redis topology refresh)이 함께 있다. 사용자 결정(2026-11-03)이다. 두 변경은 같은 `application.yml`에 들어가지만 서로 다른 설정 항목이며(`bridge.claim-batch-size` / `spring.data.redis.*`), 커밋과 검증(batch: 1장~6-3 / refresh: 6-4)을 나눴다.
> 재현 도구: [`stress-test/local-fault/bridge-batch-claim/`](../../stress-test/local-fault/bridge-batch-claim/README.md) (compose, 관측 스크립트, 실행 명령).
> 설계: [batch claim 설계](../plans/bridge-batch-claim.md).
> 범위: batch claim의 로컬 정합성(단위·실제 Redis·동시성), 장애 복구(응답 유실·앱 SIGKILL), Redis Cluster 다중 샤드·primary failover와 Redis topology refresh 적용 전후 비교, 로컬 Lua 실행 시간. **AWS 성능·failover 시험은 하지 않았다.**

## 1. 환경

| 항목 | 값 |
| --- | --- |
| 실행 위치 | 로컬 macOS, git 추적 파일 + 이번 변경만 담은 임시 worktree (추적되지 않는 예전 로컬 시험 파일은 컴파일 오류가 있어 제외) |
| Redis | Docker `redis:7.2-alpine` (7.2.16) 단일 노드, `127.0.0.1:56379`, 시험 후 삭제. ElastiCache `engine_version 7.2`와 버전만 맞춤 |
| 실행 | `BRIDGE_TEST_REDIS_PORT=56379 sh ./gradlew test --rerun-tasks` |

## 2. 시험 결과

전체: **180개, 성공 169, 실패 0, 오류 0, 건너뜀 11**(6-4의 설정 시험 포함). 마지막 실행은 6-4 failover 스택 정리 직후(2026-11-04 00:03경 KST 시작, 약 3분 소요, 정확한 시각은 기록하지 않음)이며 커밋 전 작업 트리를 담은 임시 worktree에서 실행했다. 시험 후 Redis `DBSIZE` 0(이 시험의 키만 쓰고 지움). 건너뛴 것은 외부 인프라가 필요한 기존 시험(`DlqReplayTransactionTest` 10, `CampaignCoreApplicationTests` 1)이며 이번 변경과 무관하다.

| 시험 | 수 | 확인한 것 |
| --- | ---: | --- |
| `BridgeBatchClaimRedisTest` (실제 Redis) | 8 | N=1/10/50에서 Queue −N·in-flight +N, 오래된 순, claim 중복 없음 / 연속 batch 1,2,3 → 4,5 → 빈 결과 / in-flight 9,995에서 10건 요청 시 5건, 이후 빈 결과 / 만료 batch claim 3건 전부 Queue 복구, 원본 JSON의 `\|` 보존, 이전 ACK가 새 claim 못 지움(`ack.results` not_found 3·removed 3·error 0) / Queue·in-flight 키 타입 오류 시 쓰기 전 거부(상대 키 불변) / 두 키 같은 Cluster 슬롯 |
| `BridgeBatchClaimFaultRedisTest` (실제 Redis) | 61 | 응답 유실 1회 + 동시 claim A·B·C 각 20회 반복. 4장 참고 |
| `BridgeQueueObservationRedisTest` (실제 Redis, 기존) | 1 | 단건 claim·ACK·reclaim 회귀 |
| `ParticipationBridgeBatchClaimTest` | 13 | N=1이면 단건 경로만 사용 / 한도 500·N=30 → 30×16 + 20 = 500건 / Queue 5만·50만에서 1,000·2,000건 / 부분 결과에서 회차 종료·비활성화 안 함 / 빈 결과에서 비활성 캠페인만 비활성화 / batch 중 비동기 실패·동기 예외 send의 claim은 ACK 안 함 / batch 안 send 순서 / 생성자 0·−1·51·100 거부 / ACK 실행기 거부 시 `ack.results{rejected}`·기존 `claims.ack.rejected` 각 1, ACK 미시도 |
| `ParticipationBridgeConfigBindingTest` | 3 | Spring이 설정 생성자 선택, 기본 1, `10` 반영, `0`·`-1`·`51`·`100`·`ten`에서 기동 실패 |
| `BridgeQueueServiceTest` | 14 | claimBatch 파싱·순서, 호출/이벤트/분포 지표 분리, ACK 결과 removed/not_found/error 분리, 빈 결과, 범위 밖 요청은 Redis 호출 없이 거부, 요청 초과·형식 오류 예외 + 기존 시험(지표 이름 변경 반영) |
| 기존 Bridge 시험 (`DeliveryWindow`, `Deactivation`) | 9 | 변경 없이 통과 (N=1 기존 경로) |
| Consumer·DLQ 등 나머지 | 나머지 | 회귀 통과 |

Grafana provisioning 시험(`ops/tests/grafana-provisioning-test.sh`): PASS (로컬 Grafana 컨테이너 없음 경고는 정상).

## 3. Lua 서버 실행 시간 (로컬 참고값)

조건: 이벤트 94 byte, 호출 200회씩, 호출 전 Queue에 N×200건 적재, 빈 in-flight에서 시작. `INFO commandstats`의 `cmdstat_eval`(서버 내부 실행 시간, 네트워크 제외). `redis-cli --eval`이라 EVAL 경로다(앱은 EVALSHA). 2회 반복.

| 경로 | 호출당 µs (1회차 / 2회차) | 이벤트당 µs | 실패 |
| --- | ---: | ---: | ---: |
| 기존 단건 `bridge-claim.lua` | 14.15 / 13.55 | 약 14 | 0 |
| batch N=1 | 18.14 / 17.70 | 약 18 | 0 |
| batch N=10 | 43.20 / 41.32 | 약 4.2 | 0 |
| batch N=50 | 146.93 / 143.26 | 약 2.9 | 0 |

모든 회차에서 Queue 잔량 0, in-flight = 적재 건수로 끝났다.

해석:

- batch N=1은 단건보다 약 4µs 느리다(타입 검증 등). 그래서 기본값 1은 기존 단건 Lua를 그대로 쓴다.
- N=50도 호출당 약 0.15ms로, AWS에서 관측한 claim **클라이언트** 시간(건당 평균 약 1.4~1.6ms)보다 한 자릿수 작다. 클라이언트 대기의 대부분이 Lua 실행 외(왕복·대기)라는 가설과 모순되지 않는다. 다만 이것은 서로 다른 환경의 수치 비교이며 원인 확정이 아니다.
- 이 수치는 맥 Docker 단일 노드 값이다. ElastiCache 노드 사양, 동시 접수 Lua, in-flight가 큰 상태(ZADD 비용 증가)를 반영하지 않는다. AWS에서 SLOWLOG·API p99로 다시 확인해야 한다.

## 4. 응답 유실·동시 claim 시험

| 시나리오 | 장애 주입 시점 | 입력 | 관측값 | 결과 |
| --- | --- | --- | --- | --- |
| Redis 처리 후 앱 응답 유실 | batch Lua가 ZADD+LTRIM을 끝낸 뒤, 앱에 응답 대신 `RedisSystemException` (실제 Redis 실행 후 예외를 던지는 테스트용 RedisTemplate) | 50건, N=50, `ParticipationBridge.drainQueues()` 경유 | 직후 Queue 0·in-flight 50·Kafka send 0·claim 오류 1·claim 이벤트 0 → 만료 후 reclaim 50, Queue 50·in-flight 0 → 재claim 50건 = 원본 50건 | PASS |
| A. 동시 drain | 8개 Bridge(각자 Redis 연결) 동시 시작 | 5,000건, N=50, 20회 | 매회 claim 5,000 = 고유 원본 5,000 = 고유 claim ID 5,000, Queue 0, in-flight 5,000, claim 오류 0 | PASS 20/20 |
| B. in-flight 상한 경쟁 | 8개 Bridge 동시 시작, ZCARD 연속 샘플링 | 20,000건, N=50, 상한 10,000, 20회 | 매회 claim 10,000(가장 오래된 seq 1~10,000), in-flight 10,000, Queue 10,000(seq 10,001~20,000), 관측 최대 in-flight ≤ 10,000, 중복 0 | PASS 20/20 |
| C. claim·ACK 동시 | claim 4개 + ACK 4개 Bridge 동시, ZCARD 샘플링 | 20,000건, N=50, 20회 | 매회 claim 20,000 = 고유 원본 20,000, ACK removed 20,000·not_found 0·error 0, 최종 Queue 0·in-flight 0, 관측 최대 ≤ 10,000 | PASS 20/20 |

반복 1회 소요 1.0~5.7초, 60회 합계 약 166초.

한계:

- 응답 유실은 네트워크 단절이 아니라 앱 쪽 예외로 재현했다. Toxiproxy 등으로 실제 응답만 끊은 시험은 하지 않았다.
- 동시 시험은 한 JVM 안의 별도 연결 8개다. 서로 다른 앱 프로세스·호스트는 아니며, Redis 단일 노드다.
- ZCARD 샘플링은 연속 조회라 모든 순간을 보지는 않는다. 상한 준수의 주 근거는 시험 B의 최종 in-flight = 정확히 10,000과 Lua의 원자 실행이다.
- 시험 C에서 상한에 실제로 도달했는지는 기록하지 않았다.

## 5. 앱 강제 종료 시험

| 항목 | 내용 |
| --- | --- |
| 시나리오 | batch claim 후 Kafka 확인 전 앱 프로세스 `SIGKILL`, 실제 lease 5분 만료 후 reclaim |
| 기준 코드 | 머리말의 시험 이미지(`d08e56de7359`, `Dockerfile` bootJar), 도구는 `compose-kill.yml` |
| 환경 | 시험 전용 compose 프로젝트(기존 로컬 컨테이너·볼륨과 분리): MySQL 8.4, Kafka cp-kafka 7.5.0 단일 브로커(KRaft), Redis 7.2 Cluster 3 primary(replica 없음), 앱 1대 |
| 입력 | 새 캠페인(id 2, 재고 1,000), 사용자 1001~1100 각 1회 = 100건, `BRIDGE_CLAIM_BATCH_SIZE=50` |
| 장애 주입 | ① Kafka 컨테이너 중지 → ② 100건 요청 → ③ in-flight 확인 후 앱 `docker kill --signal KILL` → ④ Kafka 시작 → ⑤ 앱 시작. lease는 score를 조작하지 않고 실제 5분을 기다림 |
| 사전 확인 | Kafka 정상 상태에서 캠페인 1에 20건: DB 20(고유 사용자·순번 20), claim 3회·20건, 발행 20, ACK removed 20, Queue·in-flight 0 |

| 시각(KST) | 단계 | Queue | in-flight | DB | 그 밖 |
| --- | --- | ---: | ---: | ---: | --- |
| 22:10:13 | Kafka 중지 | 0 | 0 | 0 | 토픽 end offset 합 20(사전 확인분) |
| 22:10:19 | 100건 요청 후, kill 직전 | 0 | 100 | 0 | HTTP 202 100건, claim score 22:10:14~15 |
| 22:10:20 | kill 직후 | 0 | 100 | 0 | 앱 DOWN |
| 22:10:43 | Kafka 복구, 앱 정지 상태 | 0 | 100 | 0 | |
| 22:10:54 | 앱 재시작 | 0 | 100 | 0 | 재시작 후 claim·발행 0 (lease 유효) |
| 22:15:12 | lease 만료 직전 | 0 | 100 | 0 | |
| 22:15:17 | reclaim | — | — | — | 앱 로그 `Expired bridge claims returned to queue. campaignId=2, count=100` |
| 22:15:24 | 재처리 완료 | 0 | 0 | 100 | 재시작 프로세스 기준 claim 2회·100건, 발행 100, ACK removed 100·not_found 0·error 0, reclaim 100 |
| 22:15:41 | 최종 | 0 | 0 | 100 | 아래 대조 |

최종 대조:

```text
요청 100 = 202 100 = DB 행 100 = 고유 user 100 = 고유 sequence 100 (1~100) = SUCCESS 100
최종 Queue 0, in-flight 0, Kafka lag 0 (offset 120/120), DLQ 0, Redis 재고 1000 → 900
Kafka 토픽 end offset 합 20 → 120: 이 시험의 발행 100건, 중복 발행 0
```

판정: **PASS**. 유실 0, 중복 발행 0. 앱 종료로 남은 claim은 lease(5분) 동안 지연된 뒤 한 번에 복구됐다.

참고:

- Kafka가 중지돼 있어 kill 전 발행 성공이 없었다. Kafka 확인 직후·ACK 전 kill(발행은 됐지만 ACK 못 함 → 중복 발행) 경우는 이 시험에서 만들지 않았다. 그 경우 중복은 DB 고유 제약에 의존한다.
- 앱 메트릭은 프로세스 재시작으로 초기화되므로 kill 전·후 값을 합산하지 않았다.
- `lag`은 Kafka가 중지된 동안 조회가 실패해 0으로 표시됐다. 그 구간 lag 값은 근거로 쓰지 않는다.
- DB `campaign.current_stock`은 두 캠페인 모두 1,000 그대로였고 Redis 재고만 줄었다. 사전 확인 캠페인도 같아 이번 변경과 무관한 기존 동작으로 보며, 확인하지 않았다.

## 6. Redis Cluster·failover 시험

| 항목 | 내용 |
| --- | --- |
| 기준 코드 | 5장과 같은 이미지(`d08e56de7359`), `BRIDGE_CLAIM_BATCH_SIZE=50`, 도구는 `compose-failover.yml` |
| 환경 | 시험 전용 compose(기존과 분리): Redis 7.2 Cluster 3 primary + 3 replica(`cluster-node-timeout 5000`), MySQL 8.4, Kafka 단일 브로커, 앱 1대. 앱 Redis 설정은 저장소 그대로(Lettuce 토폴로지 갱신 설정 없음) |
| 장애 주입 | 캠페인 슬롯의 primary 컨테이너 `docker kill --signal KILL` |

### 6-1. 다중 샤드 기능 — PASS

캠페인 1·3·4(슬롯 9842·1584·14039, primary redis-2·1·3)에 각 50건 동시 요청(Kafka 정상):

- 캠페인마다 `CLUSTER KEYSLOT` Queue = in-flight, HTTP 202 50, DB 50(고유 사용자·순번 50), Queue·in-flight 0, DLQ 0
- 앱 전체 claim 6회·150건, 발행 150, ACK removed 150, Kafka offset 0→150, 앱 로그 CROSSSLOT·ERROR 0

### 6-2. 시나리오 B: batch claim 직후 primary 장애 — 정합성 PASS, 가용성 FAIL

입력: 캠페인 5(슬롯 primary redis-2, replica redis-6), 사용자 2001~2100 100건, Kafka 중지 상태.

| 시각(KST) | 단계 | Queue | in-flight | DB | 그 밖 |
| --- | --- | ---: | ---: | ---: | --- |
| 22:21:51 | Kafka 중지 후 100건 요청 | | | | HTTP 202 100 |
| 22:22:06 | redis-2 kill 직전 | 70 | 30 | 0 | claim 1회·30건 후 멈춤(아래 참고) |
| 22:22:16 | redis-6 승격 | 70 | 30 | 0 | 새 primary에 Queue·in-flight 그대로 복제됨 |
| 22:22:17 | Kafka 시작 | | | | |
| 22:22:50 | | 70 | 30 | 30 | 버퍼의 30건 발행(published 150→180), 그 ACK는 진행 안 됨 |
| 22:24:12 | redis-2 다운 유지 | 70 | 30 | 30 | ACK error 4. **같은 슬롯 캠페인 1 API 20초 타임아웃, 다른 슬롯 캠페인 3 API도 20초 타임아웃** |
| 22:25:06 | redis-2 재시작(replica로 합류) | 70 | 30 | 30 | |
| 22:26:55 | | 70 | 30 | 30 | ACK error 16. 캠페인 3 API 회복(202, 9ms), **캠페인 1 API는 계속 타임아웃**, Bridge 정지 지속 |
| 22:27:43 | **운영자 조치: 앱 재시작** | | | | |
| 22:27:53 | 복구 | 0 | 0 | 100 | 재시작 프로세스: claim 4회·101건(캠페인 5의 Queue 70 + reclaim 30, 다른 캠페인 probe 1), 발행 101, ACK removed 101, reclaim 30 |

최종(캠페인 5): DB 100 = 고유 사용자 100(2001~2100) = 고유 순번 100 = SUCCESS 100, Queue·in-flight·lag 0, DLQ 0. Kafka offset 150→281 중 캠페인 5 발행 130 = **중복 발행 30**(ACK 실패 후 reclaim된 30건), DB 고유 제약으로 흡수.

장애 중 타임아웃 난 probe 요청(캠페인 1의 999001·999002, 캠페인 3의 999001)은 참여 키·재고 차감·DB 행이 모두 없어 서버에서도 접수되지 않았다. 202를 받은 요청(캠페인 1의 999003, 캠페인 3의 999002)만 DB에 있고 재고는 각각 949(= 1,000 − 50 − 1).

해석:

- **batch claim 경계는 지켜졌다.** 단일 `ZADD`+`LTRIM`으로 옮긴 30건과 Queue 70건이 replica 승격 후에도 그대로 있었고, 유실·DB 중복 없이 복구됐다.
- **앱이 failover를 따라가지 못했다(기존 구성 위험, batch 변경과 무관).** Lettuce는 kill된 redis-2 주소로만 재연결을 시도했고(`ConnectionWatchdog ... redis-2:7002`), 해당 슬롯의 API·Bridge·ACK가 앱 재시작 전까지 멈췄다. 장애 중에는 다른 샤드 API도 타임아웃이었다. 앱 재시작으로 복구됐고 종료된 노드로만 재연결한 로그가 있으므로 **Lettuce 클러스터 토폴로지 갱신 문제가 강하게 의심된다.** 저장소 `RedisConfig`·프로필에 topology refresh 설정이 없고, Spring Boot 4.0.4 기본값은 `spring.data.redis.lettuce.cluster.refresh.adaptive=false`, `period` 미설정이다. 원인 확정은 refresh 적용 후 A·B·C 재시험으로 한다. 운영 ElastiCache도 같은 클라이언트 설정이지만 **AWS에서 확인하지 않았다.**
- "정합성 PASS"는 중복 발행이 없었다는 뜻이 아니다. Kafka에는 130건이 발행됐고(30건 중복), DB 고유 제약이 흡수해 최종 DB 결과가 깨지지 않았다는 뜻이다.
- 첫 batch 30건 이후 claim이 멈춘 것은 Kafka 중지 상태에서 Bridge 스케줄러 스레드가 `KafkaTemplate.send`에서 대기(`max.block.ms` 60초, 메타데이터 요청 취소 로그)한 영향으로 보인다. 5장에서는 Kafka 중지 후에도 100건이 모두 claim됐으므로 조건 차이(직전 Kafka 재시작 여부 등)가 있다. 추정이며 확인하지 않았다.

### 6-3. 시나리오 A·C (refresh 적용 전) — 미실행

B에서 앱이 failover 후 재시작 전까지 해당 슬롯을 처리하지 못했으므로, 같은 구성에서 A·C를 실행해도 같은 정지가 반복될 것으로 보고 중단했다. 6-4에서 refresh 적용 후 실행했다.

### 6-4. Redis topology refresh 적용 후 재시험 — A·B·C 자동 복구·정합성 PASS, failover 구간 가용성 저하

변경(`application.yml`, 모든 프로필 공통):

```yaml
spring.data.redis.timeout: 2s                               # 미설정 시 Lettuce 기본 60초
spring.data.redis.lettuce.cluster.refresh.adaptive: true    # 연결 끊김·MOVED 등에서 즉시 갱신
spring.data.redis.lettuce.cluster.refresh.period: 30s       # 주기 갱신
```

두 설정의 역할은 다르다. topology refresh는 승격된 primary를 찾아 **자동 복구**하게 하고, `timeout: 2s`는 장애 중 요청이 60초 동안 **매달리지 않게 제한**한다.

설정 반영 시험 `RedisClusterTopologyRefreshConfigTest`: 저장소 `application.yml`을 그대로 읽어 `LettuceConnectionFactory`의 command timeout 2초, adaptive trigger 존재, periodic refresh 30초, dynamic refresh sources를 확인한다. 변경 전 yml로 돌리면 timeout `1M`으로 실패함을 확인했다.

환경·절차는 6-2와 같다(이미지 `1be8052d7549`, 3 primary + 3 replica, N=50, primary `docker kill`). **앱은 한 번도 재시작하지 않았다.**

| 시나리오 | 장애 주입 | 관측 |
| --- | --- | --- |
| 다중 샤드 회귀 | 없음 | 캠페인 1·3·4 각 50: DB 50, Queue·in-flight 0, 오류 0 (PASS) |
| B. claim 직후(6-2와 같은 절차, Kafka 중지) | in-flight 10·Queue 90일 때 redis-2 kill → 9초 뒤 redis-6 승격 | 같은 슬롯 API: kill +9초 1건 `500`(2.02초, timeout) → **+13초부터 202(약 8ms)**. 다른 슬롯 API: 장애 내내 202. 승격 후 Queue 90·in-flight 10 보존. Kafka 시작 후 8초 안에 DB 100, ACK removed 전부·error 0, reclaim 0 |
| A. claim 전(Kafka 정상) | 시험용으로 캠페인 6을 `active:campaigns`에서 SREM해 Bridge가 꺼내지 않게 한 뒤 100건 접수(Queue 100·in-flight 0) → redis-6 kill → 7초 뒤 redis-2 승격 → SADD로 되돌림 | 승격 후 Queue 100 보존, SADD 2초 안에 claim 2회(50+50)·DB 100, reclaim 0 |
| C. 처리 중(Kafka 정상) | 캠페인 7(재고 5,000)에 2,000건 병렬 5 요청 중 redis-1 kill → 8초 뒤 redis-5 승격 | HTTP 202 1,969·500 31. DB 1,969(고유 사용자·순번 1,969). failover 순간 ACK error 24 → in-flight 24가 lease 5분 뒤 reclaim·재발행. 최종 Queue·in-flight·lag 0, DLQ 0, Redis 재고 3,031 = 5,000 − 1,969 |

| 시나리오 | 정합성 | 자동 복구(앱 재시작 없음) | 무중단 가용성 |
| --- | --- | --- | --- |
| A | PASS | PASS | 미측정(장애 중 요청을 보내지 않음) |
| B | PASS | PASS | FAIL — 같은 슬롯 요청 1건 `500` |
| C | PASS | PASS | FAIL — 2,000건 중 31건 `500`, ACK 실패 24·중복 발행 24, health 조회 일시 실패 |

중복 발행: B·A는 0(Kafka offset 합 = 앱 claim 이벤트 합). C는 ACK 실패분 24건이 재발행돼 중복 24, DB 고유 제약이 흡수했다(offset 369 → 2,362 = 1,969 + 24).

`500`·타임아웃 응답 요청은 B·C 모두 참여 키·재고 차감·DB 행이 없어 서버에서 접수되지 않았다(C는 재고로 대조).

해석:

- 6-2의 "앱 재시작 전까지 정지"는 refresh 적용 후 재현되지 않았다. **원인은 Lettuce topology refresh 미설정으로 판단한다**(같은 절차에서 설정만 바꿔 결과가 달라짐).
- 남은 가용성 영향은 failover 창(약 7~13초)의 해당 슬롯 요청 실패(`500`)와 ACK 실패로 인한 중복 발행·최대 5분 지연이다. B에서 직접 호출한 다른 슬롯 API는 장애 중에도 202를 반환했다. C에서는 다른 슬롯 API 영향을 따로 측정하지 않았고, health 조회는 2초 안에 응답하지 않았다.
- `timeout: 2s` 때문에 장애 창의 요청은 매달리지 않고 약 2초 만에 실패한다. 이 값은 측정으로 정한 것이 아니며 **운영값으로 검증되지 않았다.** 정상 고부하·Redis CPU 상승·네트워크 지연·ElastiCache failover·Lua 대기·연결 대기와의 상호작용은 확인하지 않았다. 로컬 정상 상태 claim·접수 명령은 수 ms였다.
- C 장애 창에 앱 health 조회가 2초 안에 응답하지 않았고(`obs.sh`에 DOWN 표시), 같은 샤드 캠페인의 `Failed to drain campaign queue` 로그가 2회씩 있었다. 회복 후 health는 UP이었다.
- B에서 Kafka 중지 시 첫 batch(10건) 후 claim이 멈추는 현상이 다시 나타났다(8장 2번).

## 7. 미검증

- AWS 30만 비교(N=1 → 10 → 50 → 1): 2026-11-03 사용자 결정으로 이번 작업 범위에서 제외. 실제 Bridge 발행률·Queue·완료 시간 개선 여부는 모른다(기본값 1이라 배포 시 동작은 기존과 같다)
- AWS ElastiCache에서의 failover 동작과 refresh 설정의 효과(엔드포인트·DNS 방식이 로컬과 다를 수 있음)
- `timeout: 2s`·`period: 30s`의 적정값(측정으로 정하지 않음), 정상 고부하에서 2초 timeout 발생 여부
- refresh 적용 상태의 반복 failover(같은 슬롯 연속 장애), replica 없는 샤드 장애
- Kafka 발행 성공 후 ACK 전 앱 kill(중복 발행 경로). 기대: Kafka 발행 2회, DB INSERT 1회, 최종 Queue/in-flight/lag 0
- 최종 커밋을 깨끗한 checkout에서 전체 시험
- 네트워크 수준의 Redis 응답 유실(Toxiproxy)
- 동시 접수 부하 중 Lua 대기(API 지연)
- 서로 다른 앱 프로세스·호스트에서의 동시 claim(3장 동시 시험은 한 JVM 안의 연결 8개)

## 8. 남은 문제

| 순위 | 문제 | 근거 | 영향 | batch 변경과 관계 |
| --- | --- | --- | --- | --- |
| 1 | (로컬 해결) Redis primary failover 후 앱이 승격된 primary를 따라가지 못해 앱 재시작 전까지 정지 | 6-2 재현 → 6-4 topology refresh·timeout 적용 후 A·B·C 자동 회복. AWS 미확인 | 적용 후 남은 영향: failover 창 약 7~13초의 해당 슬롯 요청 실패, ACK 실패분 중복 발행·최대 5분 지연 | 무관(기존 구성). 같은 브랜치에서 수정 |
| 2 | Kafka 중지 시 Bridge claim이 첫 batch 후 멈춤 | 6-2·6-4 B에서 관측(30건·10건 후 멈춤), `KafkaTemplate.send` 대기(`max.block.ms` 60초) 추정. 5장에서는 재현 안 됨 | 처리 지연(원본은 Redis에 남음) | 무관(기존 동기 send 경로), 원인 미확인 |
| 3 | 추적되지 않는 예전 로컬 시험 파일 때문에 작업 폴더 그대로 `gradlew test` 컴파일 실패 | 1장 | 로컬 시험 불편. 이번 시험은 임시 worktree 사용 | 무관 |

1번 수정은 사용자 결정으로 같은 브랜치에 넣었다. 기본 `claim-batch-size=1`이면 기존 단건 경로이므로 코드를 병합해도 동작은 같다. 실제 환경에서 N=10/50을 켜는 것은 AWS 성능 비교와 failover 보완 이후로 분리한다.

관찰 메모(문제로 확정하지 않음): DB `campaign.current_stock`이 참여 후에도 1,000 그대로였고 Redis 재고만 줄었다(5장). 사전 확인 캠페인도 같았다. Redis가 재고 원장이고 DB 필드는 갱신 대상이 아닌 설계일 수 있으며 확인하지 않았다.

## 9. failover 구간 실패를 줄이는 선택지 (검토만, 미적용)

6-4 적용 후에도 failover 구간(로컬 약 7~13초)에는 해당 슬롯 요청이 `500`으로 실패하고, ACK 실패분은 중복 발행·최대 5분 지연이 생긴다. 이를 더 줄일수록 지연·부하·구현 복잡도가 늘어난다. **아래는 검토이며 이번 변경에 넣지 않았다.**

| 방법 | 장점 | 트레이드오프 |
| --- | --- | --- |
| 서버 내부 Redis 재시도 | 일시적인 500/503 감소 | API 응답이 수초 느려지고 대기 요청이 쌓임 |
| 재시도 횟수 증가 | failover 중 성공 가능성 증가 | Redis 복구 순간 요청이 몰리는 재시도 폭주 |
| Redis timeout 증가 | 느린 요청도 성공할 가능성 증가 | 장애 요청이 오래 매달리고 장애가 전파될 수 있음 |
| Redis timeout 감소 | 빨리 실패해 서버 보호 | 정상적인 순간 지연도 실패로 처리될 수 있음 |
| 클라이언트 재시도 | 서버 구현이 단순 | 응답만 유실된 경우 이미 성공한 요청을 다시 보냄(현재는 중복 참여 키로 거절됨) |
| ACK 즉시 재시도 | 최대 5분 reclaim 지연 감소 | Kafka 성공 여부가 불명확한 경우 처리가 복잡해짐 |
| lease 단축 | 복구 속도 증가 | 아직 처리 중인 이벤트까지 reclaim해 중복 발행 증가 |
| 요청을 별도 큐에 먼저 저장하고 202 | Redis 장애 중에도 빠르게 202 | 재고 확인·차감·중복 확인·순번 결정이 뒤로 밀려 **202 = 선착순 접수 성공**이라는 계약이 바뀜. Kafka·DB·Redis 사이 정합성 설계가 크게 복잡해짐 |

**재시도 폭주가 가장 큰 위험이다.** 장애 중 대기하던 요청이 replica 승격 순간 한꺼번에 재시도하면 새 primary가 과부하돼 다시 timeout이 날 수 있다. 재시도를 넣는다면 즉시 반복하지 않고 exponential backoff와 jitter를 둔다(예: 100~200ms → 300~500ms → 700~1,000ms, 총 대기 상한 2~3초). 다만 로컬 failover가 7~13초였으므로 2~3초 재시도로는 대부분 여전히 실패하고, 15초까지 늘리면 성공률은 오르지만 API 요청이 최대 15초 매달린다.

**`timeout: 2s`도 측정 전 값이다.** 장애 요청이 60초 매달리지 않게 하지만, 정상 고부하에서 Redis 응답이 순간적으로 2초를 넘으면 장애가 아닌데도 실패한다. AWS 부하 시험에서 Redis 명령 p99, 연결 대기 시간, Lua 실행 시간, timeout 발생 건수, failover 복구 시간을 측정한 뒤 정한다.

**현재 구조에서 현실적인 순서**(제안, 미승인):

1. topology refresh로 자동 복구 — 적용함(6-4)
2. Redis timeout으로 장시간 대기 차단 — 적용함(값은 측정 전)
3. 연결 장애에 한해 짧게 1~3회 서버 재시도
4. exponential backoff + jitter
5. 끝까지 실패하면 `500` 대신 `503`으로 응답(현재는 `500`)
6. 클라이언트는 같은 `campaignId + userId`로 제한적으로 재시도(중복 참여 키가 이중 접수를 막음)
7. Consumer·DB는 계속 중복을 허용하고 멱등 처리 — 현재 구조 유지
8. AWS 측정 후 timeout·재시도 시간 확정

목표는 완전 무중단이 아니라 다음이다.

> 장애 중 일부 요청은 빠르게 503으로 실패할 수 있지만, 202를 받은 요청은 유실하지 않고 앱은 재시작 없이 자동 복구한다.

이번 변경으로 장기 정지 방지(1)와 장시간 대기 차단(2)까지 했고, 6-4에서 202 요청의 정합성을 확인했다. 3~6은 AWS에서 failover 시간과 정상 Redis p99를 먼저 측정한 뒤 별도 변경으로 다룬다.
