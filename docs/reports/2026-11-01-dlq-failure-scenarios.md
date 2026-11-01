# 2026-11-01 DLQ 배치 역할 확정을 위한 실패 시나리오 목록과 검증 계획

> 기준 코드: `main` `c76be3f` (#111 머지 시점). spring-kafka 4.0.4, Spring Boot 4.0.4.
> 표기: **[확정]** 코드 또는 재현으로 확인 / **[추정]** 코드 동작상 가능성이 높음, 미검증 / **[미확인]** 확인 필요.
> §2~§3은 수정 전 코드 판독 결과다. `fix/consumer-dlq-retry-boundary`의 변경과 남은 검증은 §8에 있다. 검증을 진행하면 §8의 상태를 갱신한다.

## 1. 목적

작업 순서 1단계 "DLQ 배치 역할 확정"의 입력 자료다. 202 접수 이후 실패 유형을 빠짐없이 나열하고, 유형마다 다음을 정한다.

- 지금 코드가 실제로 어떻게 처리하는가 (재시도 / DLQ 격리 / 수동 재처리 / 최종 실패)
- 어떤 방법으로 검증하는가
- 무엇을 먼저 검증하는가

완료 기준: DB 일시 장애, 잘못된 메시지, 중복 메시지의 처리 결과가 각각 명확하다.

## 2. 현재 처리 요약 (코드 기준)

| 구성요소 | 실패 시 동작 | 근거 |
|---|---|---|
| Consumer 배치 insert | 실패하면 건별 `INSERT IGNORE`로 넘어감. 건별로 실패한 건은 DLQ(`INSERT_FAILED`, `CONDITIONALLY_REPLAYABLE`)에 넣고 그 배치는 ACK하지 않음 | `ParticipationEventConsumer.java` |
| Consumer ACK 보류 | **재전달되지 않음.** MANUAL ACK 모드에서 정상 반환하면 fetch 위치는 이미 앞으로 가 있어서, 다음 배치 ACK가 실패 건까지 넘어서 커밋한다. 코드 주석의 "Kafka will redeliver after recovery"는 사실과 다르다 | [확정 재현] [T06 재현 기록](2026-10-01-t06-consumer-redelivery-verification.md) §6 |
| Consumer 파싱 | JSON 파싱 실패, `sequence` 없음 → DLQ(`NON_REPLAYABLE`) 후 ACK | `ParticipationEventConsumer.parseRecords` |
| 중복 | DB 유니크 키 `uk_campaign_user(campaign_id, user_id)`와 `INSERT IGNORE`로 제거 | `V3__add_unique_constraint_campaign_user.sql` |
| DLQ 저장 | `@Transactional publishAndStore` 안에서 `dlq_message` 저장과 DLQ 토픽 발행을 각각 try-catch로 감쌈. 실패는 로그만 남기고 삼킴. 토픽 발행은 비동기 `send`이고 결과를 확인하지 않음 | `DlqMessageService.java` |
| Bridge 발행 실패 | DLQ로 보내지 않음. Redis claim이 남아 임대가 만료되면 큐로 돌아가 재시도. 재시도 상한 없음. `createBridgePayload`를 호출하는 곳이 없음 | `ParticipationBridge.publishAsync`, `reclaimExpiredClaims` |
| Replay 정책 | `NON_REPLAYABLE` → FINAL_FAIL. 필수 필드 누락이나 없는 캠페인 → FINAL_FAIL. 이미 SUCCESS 이력 있음 → SKIP. 그 외 → 원래 토픽으로 재발행 | `DlqReplayPolicyService.java` |
| Replay 실행 | 한 실행을 하나의 `@Transactional`로 처리. Kafka 발행 실패는 FAILED로 기록하고 PENDING 유지 | `DlqReplayService.java` |
| 에러 핸들러 | `ErrorHandler`·`BackOff`·`nack`·`seek`·`RebalanceListener`를 설정하지 않음 → spring-kafka 기본 동작을 따름 | `KafkaConfig.java` |

## 3. 실패 시나리오 목록

### 3.1 검증 방법 분류

| 코드 | 방법 | 예 |
|---|---|---|
| **M1** | 코드·공식 문서 확인 | 기본값, 호출 관계, 설정 존재 여부 |
| **M2** | 단위 테스트 (mock) | Consumer·Replay 분기, ACK 호출 여부 |
| **M3** | 로컬 통합 테스트 (MySQL·Kafka 실제 연결) | `integration/full/DlqReplayIntegrationTest` 확장 |
| **M4** | 로컬 장애 주입 | `repro/t06/run-scenario.sh` (iptables REJECT/DROP, 앱 재기동) |
| **M5** | 실환경 하네스 | `stress-test/harness/run.sh` + 고장 시험. 비용 발생, 사용자 승인 필요 |

### 3.2 우선순위 기준

| 등급 | 기준 |
|---|---|
| **P0** | 202 이후 유실이나 잘못된 데이터가 생길 수 있고, DLQ 역할 결정에 직접 영향을 줌 |
| **P1** | 유실 가능성은 낮지만 재시도 상한·격리·운영 절차를 정하는 데 필요함 |
| **P2** | 중복 제거 등 기존 장치로 결과가 보장될 것으로 예상되며, 확인 차원 |
| **범위 밖** | DLQ로는 막을 수 없음. 정합성 복구 배치에서 탐지만 가능하거나 별도 과제 |

### 3.3 ① API → Redis

| # | 시나리오 | 현재 동작 | 상태 | 검증 방법 | 우선순위 |
|---|---|---|---|---|---|
| A1 | Lua 실행 중 Primary 장애 | 스크립트가 원자적이라 일부만 반영되지 않음 | 구멍 아님 | 기존 기록 인용 (`participation-loss-holes.md` Ⅱ-1) | 범위 밖 |
| A2 | 복제 전 Primary 장애 (202를 받은 건이 승격 후 사라짐) | 유실 | **구멍 [확정 재현]**, 대책 미적용 | 기존 기록 (loss-holes A, WAIT 검토) | 범위 밖 |
| A3 | Redis OOM | 202 이전에 거부. 단 참여 키 OOM으로 Bridge가 영구 정지한 사례 있음 | 2026-10-22 150만 FAIL, 대책 미적용 | 별도 과제 | 범위 밖 |

### 3.4 ② Redis 큐 → Bridge → Kafka

| # | 시나리오 | 현재 동작 | 상태 | 검증 방법 | 우선순위 |
|---|---|---|---|---|---|
| B1 | Kafka 발행 실패 (브로커 다운·타임아웃), 장시간 지속 | claim이 남아 임대 만료 후 재시도. 상한·격리 없음 | [확정 코드] 장시간 동작은 미검증 | M4: 로컬 Kafka 정지 N분 → 재기동 후 Redis 큐·claim 잔량 0, DB 건수 = 202 건수 | P1 |
| B2 | 발행은 성공했는데 Redis ACK 실패나 ACK 실행 거부 | claim이 회수돼 다시 발행 → 중복 → 유니크 키로 제거 | [확정 코드] #109·#111에서 일부 검증 | 기존 기록 인용 (`2026-10-20-pr109-bridge-inflight-verification.md`) | P2 |
| B3 | 발행 중 앱 강제 종료 (SIGKILL) | claim이 남아 회수 후 재발행 → 유실 없음 기대 | PR109 문서에서 검증. **loss-holes B는 RPOP 시절 기준이라 구버전** | 기존 기록 인용 + loss-holes 정정 | P2 |
| B4 | 프로듀서 버퍼 가득 참 (`max-block-ms` 60s) | `send`가 최대 60s 막힘 → Bridge 스케줄러 스레드가 멈춰 드레인 정지 | [추정] | M1: 스케줄러 스레드 구성 확인 → M4: 브로커 응답 지연 주입 | P1 |
| B5 | 잘못된 메시지가 Redis 큐에 있음 | 파티션 키만 `campaignId`로 대체하고 그대로 발행 → Consumer에서 처리 (C1~C4로 이어짐) | [확정 코드] | M2 | P2 |
| B6 | Bridge 영구 정지 (OOM 등) | 큐가 쌓이기만 함. 유실은 아니지만 DB 반영 안 됨 | 2026-10-22 사례 | 별도 과제 | 범위 밖 |

### 3.5 ③ Kafka

| # | 시나리오 | 현재 동작 | 상태 | 검증 방법 | 우선순위 |
|---|---|---|---|---|---|
| K1 | 브로커 1대 장애 (RF3, `acks=all`) | 리더가 넘어가고 계속 동작 기대 | 미검증 | M5 (3 브로커 환경에서만 가능) | P2 |
| K2 | **retention보다 긴 Consumer 정지** (긴 DB 장애 등) | 커밋 전에 메시지가 지워져 유실 | **[미확인]** 저장소에서 토픽·브로커 retention 설정을 찾지 못함 → 브로커 기본값으로 추정 | M1: 실 브로커 `kafka-configs --describe` | P1 |
| K3 | 배치 처리 도중 리밸런싱 | 커밋된 offset부터 다시 소비 → 중복 → 유니크 키로 제거 | [확정 재현] T06 §7 | 기존 기록 인용 | P2 |

### 3.6 ④ Consumer 파싱·검증 (잘못된 메시지)

| # | 시나리오 | 현재 동작 | 상태 | 검증 방법 | 우선순위 |
|---|---|---|---|---|---|
| C1 | JSON 파싱 실패 | DLQ `JSON_PARSE_ERROR` / `NON_REPLAYABLE`, ACK → Replay 시 FINAL_FAIL | [확정 코드] | M2 (이미 있으면 인용), M3 | P2 |
| C2 | `sequence` 없음 | DLQ `MISSING_SEQUENCE` / `NON_REPLAYABLE`, ACK | [확정 코드] | M2, M3 | P2 |
| C3 | **없는 `campaignId`** | `INSERT IGNORE`가 FK 위반을 경고로 바꿔 행을 건너뜀 → DLQ로 가지 않고 ACK, `consumer.db.committed`에 성공으로 집계 | **[추정]** | M3: 없는 캠페인 ID 메시지 발행 → `participation_history`·`dlq_message` 건수, `SHOW WARNINGS`, 지표 확인 | **P0** |
| C4 | **`campaignId`나 `userId`가 null (`sequence`는 있음)** | `INSERT IGNORE`가 NOT NULL 위반을 묵시 기본값(0)으로 바꿈 → `campaign_id=0`이면 FK로 건너뜀, `user_id=0`이면 **잘못된 행이 저장되고** 이후 null 사용자 메시지는 유니크 키로 무시됨 | **[추정]** | M3: 같은 방식. `user_id=0` 행 생성 여부 | **P0** |
| C5 | 형식은 맞지만 값이 이상함 (음수 ID, 마감된 캠페인 등) | 검증 없이 저장 | [확정 코드] | M1 → 정책 결정 (정상 흐름에서는 API가 값을 만들어 넣으므로 발생 경로부터 확인) | P2 |

### 3.7 ⑤ Consumer → DB (DB 일시 장애)

| # | 시나리오 | 현재 동작 | 상태 | 검증 방법 | 우선순위 |
|---|---|---|---|---|---|
| D1 | DB 완전 장애 (연결 거부·무응답), 리밸런싱 없음 | 다음 배치 ACK가 실패 건을 넘어 커밋 → `dlq_message`에 저장됐을 때만 수동 Replay로 복구 | **구멍 [확정 재현]** 54,000건 중 60건 (T06 §6) | 수정 후 M4 재검증: `./run-scenario.sh reject 150`, `drop 150` → 유실 0 | **P0** (수정 대상) |
| D2 | DB 장애 중 리밸런싱 | 재소비로 유실 0 | [확정 재현] T06 §7 | 수정 후 M4: `RESTART_AT=60` → 유실 0·중복 0 | P2 (회귀 확인) |
| D3 | 일부 행만 실패 (deadlock, lock wait timeout) | 건별 처리로 넘어가 실패 건만 DLQ, ACK 보류 → D1과 같은 경로 | [추정] | M2: 일부 행 예외 mock → ACK·DLQ 호출 확인. M3: 행 잠금으로 lock wait 유발 | P1 |
| D4 | **건별 처리가 `max.poll.interval`(600s) 초과** | 배치 최대 500건 × 커넥션 대기 최대 20s → 그룹 이탈과 리밸런싱 반복 가능 | **[추정]** T06 H4 | M1: Hikari `connection-timeout`, `max-poll-records`(prod) 확인 → M4: 무응답(DROP) 장기 주입 + 큰 배치 | **P0** |
| D5 | DB 느림 (죽지는 않음) | lag 증가, 유실 없음 기대 | — | 성능 단계(4단계)에서 다룸 | 범위 밖 |
| D6 | DB 디스크 부족·읽기 전용 전환 | D1과 같은 경로 예상 | 미검증 | M4: `SET GLOBAL read_only=ON` | P2 |
| D7 | **리스너가 예외를 던짐** (`try` 밖: 지표 등록, 직렬화 등) | 에러 핸들러를 설정하지 않아 spring-kafka 기본 `DefaultErrorHandler`를 따름. 일정 횟수 재시도 후 **로그만 남기고 건너뛰는** 것으로 알려져 있음 | **[미확인]** 4.0.4 기본값 | M1: spring-kafka 4.0 문서·소스 → M2/M3: 리스너에서 예외 유발 시 offset 커밋 여부 | **P0** |

### 3.8 ⑥ DLQ 저장 경로

| # | 시나리오 | 현재 동작 | 상태 | 검증 방법 | 우선순위 |
|---|---|---|---|---|---|
| Q1 | **DB 장애 중 `dlq_message` 저장 실패** | `@Transactional` 시작 시점에 커넥션 획득 실패 → 내부 try-catch 이전에 예외 → Consumer가 삼킴. 파싱 실패 건은 그래도 ACK → **원본 유실**. INSERT_FAILED 건은 D1 경로와 겹쳐 흔적이 남지 않음 | **[추정]** T06 §2.5. 2026-10-01 재현 조건에서는 DLQ 전송 시점에 DB가 이미 복구돼 재현되지 않음 | M4: 장애 구간 > 배치 건수 × 20s로 길게 주입. 또는 M3: DB 정지 상태에서 파싱 실패 메시지 발행 | **P0** |
| Q2 | DLQ 토픽 발행 실패 | 비동기 `send` 결과를 확인하지 않아 실패를 알 수 없음 | [확정 코드] | M3: DLQ 토픽 미생성·브로커 정지 | P1 |
| Q3 | `dlq_message`와 DLQ 토픽 중 한쪽만 성공 | 두 저장소가 어긋남. Replay는 DB 기준이므로 토픽은 보조 기록 | [확정 코드] | M1 → 역할 결정 (토픽을 유지할지) | P1 |
| Q4 | 같은 이벤트의 DLQ 행이 여러 개 생김 (재소비할 때마다 다시 적재) | 여러 행이 생기지만 Replay 시 SKIP·중복 제거 | [추정] | M3 | P2 |
| Q5 | Slack 장애 | `@Async`로 분리돼 있고 예외도 잡음 → 영향 없음 | [확정 코드] | 코드 인용 | P2 |

### 3.9 ⑦ DLQ Replay 배치

| # | 시나리오 | 현재 동작 | 상태 | 검증 방법 | 우선순위 |
|---|---|---|---|---|---|
| R1 | 재발행은 성공했는데 상태 갱신 트랜잭션이 롤백됨 | PENDING으로 남아 다음 실행에서 다시 발행 → 유니크 키로 제거 | [추정] | M3: 처리 중 예외 주입 → 재실행 후 DB 건수 | P2 |
| R2 | **Replay 실행 두 개가 동시에 돎** | `JobExecutionAlreadyRunningException`은 같은 Job 파라미터일 때만 발생. 실행마다 `replayExecutionId`가 달라서 동시 실행이 가능할 수 있음 → 같은 메시지를 두 번 발행 (중복은 제거됨) | **[미확인]** | M1: 컨트롤러 실행 경로·파라미터 확인 → M3: 동시 요청 2건 | P1 |
| R3 | Replay 중 Kafka 장애 | FAILED 기록, PENDING 유지 → 재시도 가능 | [확정 코드] | M3 | P2 |
| R4 | 재발행한 메시지가 다시 실패함 (DB 아직 복구 전) | 새 DLQ 행 생성 → Replay 반복. 상한 없음 | [추정] | M3 | P1 |
| R5 | Replay 도중 앱 종료 | 하나의 트랜잭션이라 상태 갱신이 롤백되고 이미 발행한 것만 남음 → R1과 같음 | [추정] | M3 | P2 |
| R6 | 캠페인 종료·재고 소진 뒤 Replay | 202 때 이미 Redis 재고를 차감했으므로 재처리해도 맞는 동작. Replay가 재고를 다시 확인하지 않음 | [추정] | M1 + M3: 재고 대비 DB 건수 | P2 |

### 3.10 ⑧ 정합성 복구 배치·운영

| # | 시나리오 | 비고 | 검증 방법 | 우선순위 |
|---|---|---|---|---|
| X1 | 정합성 복구 배치와 DLQ Replay를 동시에 실행 | 서로 영향을 주는지 미확인 | M1 → M3 | P2 |
| X2 | 배포·롤백 중 구버전과 신버전이 함께 실행 (메시지 형식 차이) | #111 롤백 가드가 일부 다룸 | 기존 기록 인용 | P2 |
| X3 | ASG 축소·교체 중 Consumer 이탈 | K3와 같은 경로 | 기존 기록 인용 | P2 |

## 4. 진행 순서 (제안)

| 순서 | 대상 | 방법 | 산출물 | 비용 |
|---|---|---|---|---|
| 1 | D7, R2, K2, D4 설정값, B4 스레드 구성 | M1 | 이 문서 상태 갱신 | 없음 |
| 2 | C3, C4, Q1, D3 | M2·M3 (로컬) | 재현 결과, 테스트 코드 | 로컬 |
| 3 | 1·2 결과로 **DLQ 역할 확정**: 실패별 처리 표 + 운영 절차 | 결정 | 처리 표 (재시도 / DLQ 격리 / 수동 재처리 / 최종 실패) | 없음 |
| 4 | D1·D2·D4·Q1 수정 후 재검증 (2단계로 넘어감) | M4 | 유실 0·중복 0 기록 | 로컬 |
| 5 | B1, Q2, R4 등 P1 나머지 | M3·M4 | 상태 갱신 | 로컬 |
| 6 | K1 등 실환경 전용 항목 | M5 | 하네스 증거 | **실환경, 사용자 승인 필요** |

1~3이 1단계(역할 확정) 범위다. 4부터는 2단계(실패·복구 경로 구현 검증)에 해당한다.

## 5. 역할 결정 초안 (확정 전)

| 실패 유형 | 초안 |
|---|---|
| DB 일시 장애 (D1·D3·D4·D6) | Consumer가 실패 지점부터 다시 처리(`nack`/seek 또는 `DefaultErrorHandler` + BackOff). 상한을 넘으면 DLQ로 보냄. DLQ 적재와 ACK 보류를 동시에 하는 지금의 이중 경로는 없앰. DB 연결 실패면 건별 처리로 넘어가지 않고 배치 전체를 재시도 |
| 잘못된 메시지 (C1~C5) | insert 전에 필수 필드·캠페인 존재를 검증하고 DLQ(`NON_REPLAYABLE`)에 격리 → FINAL_FAIL. `INSERT IGNORE`가 중복 외의 오류를 삼키지 않게 함 |
| 중복 메시지 (B2·B3·K3·Q4·R1) | DB 유니크 키로 제거. DLQ는 관여하지 않음 |
| DLQ 저장 실패 (Q1·Q2) | 원본 ACK 금지 (2단계 완료 기준과 같음) |
| Replay 배치 | 유실 복구 수단이 아니라, 재시도 상한을 넘긴 건을 운영자가 판단해 수동으로 재처리하는 용도로 한정. 동시 실행 방지(R2)와 재실패 상한(R4) 필요 |
| 범위 밖 (A1~A3·B6·K2·D5) | DLQ로 막을 수 없음. 정합성 복구 배치로 탐지하거나 별도 과제로 다룸 |

## 6. 정정이 필요한 문서·코드

| 대상 | 내용 |
|---|---|
| `ParticipationEventConsumer.java` ACK 보류 주석·로그 | "Kafka will redeliver after recovery" → 사실과 다름 (T06 §10.1) |
| `docs/current/2026-10-01-participation-loss-holes.md` B, 6 | RPOP 기준 설명 → 현재는 claim·임대 방식. Bridge 발행 실패는 DLQ로 가지 않고 claim 재시도 |
| T06 §10.2 목록 (`README.md`, `ARCHITECTURE.md`, 블로그 등) | "ACK 보류 → 자동 재전달" 표현. 미착수 상태 그대로 |

## 7. 참고

- [T06 재현 기록](2026-10-01-t06-consumer-redelivery-verification.md) — D1·D2·D4·Q1 재현과 후속 작업 (§10)
- `docs/current/2026-10-01-participation-loss-holes.md` — A·B 구멍 정리 (B·6은 구버전, 로컬 미추적 기록)
- `docs/current/2026-10-20-pr109-bridge-inflight-verification.md` — Bridge claim 방식 검증 (로컬 미추적 기록)
- `docs/plans/accepted-202-failure-verification.md` — 202 접수 이후 장애 검증 계획
- `app/campaign-core/repro/t06/` — DB 장애 주입 재현 환경 (로컬 미추적)

## 8. 변경 반영 상태 (`fix/consumer-dlq-retry-boundary`, 2026-11-01)

### 8.1 원칙

배치의 모든 메시지가 **어딘가에 확실히 저장됐을 때만 ACK**한다.

- 정상 메시지 → `participation_history`
- 메시지 자체의 문제 → `dlq_message` (DB 커밋까지 성공해야 완료)
- 둘 다 못 했음 → ACK하지 않고 `nack(0, backoff)`로 배치 처음부터 다시 받음

### 8.2 코드 변경

| 변경 | 내용 | 관련 시나리오 |
|---|---|---|
| 배치 재시도 | 배치 저장이 무결성 위반 외의 예외로 실패하면 건별 처리 없이 즉시 `nack(0)`. 건별 처리 중에도 무결성 위반 외의 예외가 나면 남은 행을 처리하지 않고 즉시 `nack(0)`. 백오프는 `kafka.consumer.retry-backoff-ms`(기본 1000ms) | D1, D3, D4, D6 |
| 메시지 탓만 DLQ | `DataIntegrityViolationException`만 메시지 자체의 문제로 보고 DLQ(`INSERT_FAILED`)에 보관한다. 권한·읽기 전용·디스크 부족처럼 분류가 불확실한 오류는 DLQ로 보내지 않고 재시도한다 | D6, 시스템 오류의 대량 DLQ 방지 |
| 중복만 무시 | `INSERT IGNORE` → `INSERT … ON DUPLICATE KEY UPDATE id = id`. 필수 컬럼 `updated_at`을 INSERT에 추가했다. 이전 SQL은 `updated_at`이 빠져 있어서, IGNORE만 제거하면 정상 메시지도 1364 오류로 실패한다. 무결성 오류를 "중복 성공"으로 처리하던 catch를 제거했다. 배치·건별 SQL을 하나로 통일했다(JPA `insertSuccess` 삭제) | C3, C4 |
| 필수 값 검증 | 읽은 결과가 null이거나, `campaignId`·`userId`·`sequence` 중 하나라도 null 또는 0 이하이면 DB 저장 없이 `INVALID_PAYLOAD` / `NON_REPLAYABLE`로 보관한다(`MISSING_SEQUENCE` 대체) | C4 |
| DLQ 저장 실패 전파 | `DlqMessageService.publishAndStore`는 `dlq_message` 저장이 커밋되지 않으면 예외를 던진다(`@Transactional` 제거, repository 자체 트랜잭션으로 커밋). Consumer는 하나라도 실패하면 배치 전체를 `nack(0)`한다. 파싱 오류와 DLQ 저장 오류는 별도 단계로 분리했다 | Q1 |
| DLQ 토픽 | 보조 기록. 비동기 `send` 결과를 `whenComplete`로 확인해 실패를 지표로 남긴다 | Q2, Q3 |
| 지표 | `consumer.batch.retried{reason}`, `dlq.db.stored`, `dlq.db.store.failed`, `dlq.topic.publish.failed` 추가. `consumer.db.committed`는 신규 INSERT 수가 아님을 설명에 명시했다 | — |

### 8.3 이 변경으로 생기거나 남는 위험

- **DLQ 기록 중복 (Q4):** DLQ를 저장한 뒤 같은 배치가 `nack`되면 같은 원본이 `dlq_message`에 다시 쌓인다. 참여 이력은 유니크 키로 중복이 막히고, Replay는 SKIP한다. 원본 partition·offset 기준 중복 방지는 마이그레이션이 필요해서 배치 작업으로 넘긴다.
- **파티션 정지:** DB 장애가 계속되는 동안 그 Consumer가 맡은 파티션들이 멈춘다. Kafka 보존 기간을 넘기면 유실된다(K2, 보존 기간 미확인).
- **독성 메시지:** 무결성 위반이 아닌 오류를 계속 일으키는 메시지가 있으면 그 파티션이 무한 재시도한다. `consumer.batch.retried` 알림으로 탐지해야 한다.
- **신규 INSERT 수 집계 불가:** Connector/J 기본값(`useAffectedRows=false`)에서는 중복 행도 영향 행 수 1로 돌아온다. 그래서 JDBC 반환값으로는 신규 행 수를 셀 수 없다. 신규 행 수는 DB 건수로 확인한다.
- **SQL 변경의 성능 영향:** 처리 경로(hot path)의 SQL이 바뀌었다. 정합성 변경이지만 3단계 기준선 측정에서 전후를 비교해야 한다.

### 8.4 검증 상태

| 검증 상황 | 기대 결과 | 단위 테스트 (mock) | 로컬 실제 연결 (2026-11-01) |
|---|---|---|---|
| DB 장애 후 복구 | 실패한 배치가 다시 처리됨 | ✅ | ✅ `reject 150`·`drop 150` 모두 유실 0 (L2) |
| 건별 처리 중 DB 장애 | 즉시 중단하고 재시도함 | ✅ | ⬜ 장애 시점을 건별 처리 중으로 맞춘 실행은 하지 않음 |
| 정상·불량 메시지 혼합 | 정상은 DB, 불량만 DLQ | ✅ | ✅ L1, L3 |
| DLQ DB 저장 실패 | ACK하지 않고 재시도함 | ✅ | ✅ L3: 장애 중 DLQ 0건·committed 정지, 복구 후 불량 20건 전부 보관 |
| 같은 배치 재전달 | 참여 이력에 중복 행 없음 | — | ✅ L1 재전달, L2 재기동 시나리오 중복 0 |
| 필수 값 누락·없는 캠페인 | 성공으로 잘못 집계하지 않음 | ✅ | ✅ L1: 없는 캠페인은 FK 위반 → `DataIntegrityViolationException` → DLQ |
| 정상 메시지 저장 | `updated_at` 포함 INSERT 성공 | ✅ | ✅ L1 |

- 단위 테스트: clean worktree(추적 파일만) 기준 `./gradlew test` 54건 중 실패 0, 건너뜀 1. 로컬 작업 폴더에는 컴파일되지 않는 미추적 테스트 12개가 있어서 그 자리에서는 실행할 수 없다.
- **로컬 단일 브로커·단일 앱 기준으로 유실 0을 확인했다.** 실환경(RDS, 3 브로커, ASG 다중 인스턴스) 검증은 하지 않았다.

### 8.5 로컬 실측 (2026-11-01)

**조건**

| 항목 | 값 |
|---|---|
| 코드 | 브랜치 `fix/consumer-dlq-retry-boundary` = `c76be3f` + 미커밋 변경 (이미지 `campaign-core:dlq-fix`). 대조군 `main` `c76be3f` (이미지 `campaign-core:main-c76be3f`) |
| 환경 | `repro/t06/docker-compose.yml`: MySQL 8.4, cp-kafka 7.5.0 단일 브로커(토픽 10 파티션, DLQ 3 파티션), Redis 7.2 클러스터 3노드, 앱 1개 |
| 앱 설정 | `local` 프로필, `max-poll-records` 100(prod와 같음), `max-poll-interval` 600s, Hikari `connection-timeout` 20s, `retry-backoff-ms` 기본 1000 |
| 부하 (L2) | 200건/s × 270s = 54,000건 (장애 전 30s, 장애 150s, 복구 후 90s). key = userId = sequence |
| 장애 주입 | mysql 네트워크 네임스페이스에서 iptables로 3306 인바운드 `REJECT`(tcp-reset) 또는 `DROP` |

**L1: 메시지 유형별 (메시지 9건 + 같은 정상 메시지 2건 재전달)**

| 메시지 | `main` | 브랜치 |
|---|---|---|
| 정상 (userId 1, 8) | 저장. **`updated_at = 0000-00-00`** | 저장, `updated_at` 정상 |
| 중복·재전달 | 행 추가 없음 | 행 추가 없음 |
| 없는 캠페인 (999) | **흔적 없이 사라짐** | DLQ `INSERT_FAILED` / `CONDITIONALLY_REPLAYABLE` |
| `userId` null | **`user_id = 0` 행 저장** | DLQ `INVALID_PAYLOAD` |
| `campaignId` null | **흔적 없이 사라짐** | DLQ `INVALID_PAYLOAD` |
| `sequence = 0` | **저장** | DLQ `INVALID_PAYLOAD` |
| `null` / 깨진 JSON | DLQ `JSON_PARSE_ERROR` ×2 | DLQ `INVALID_PAYLOAD` / `JSON_PARSE_ERROR` |
| 합계 | 참여 4행(잘못된 행 2), DLQ 2 | 참여 2행, DLQ 6, 잘못된 행 0 |

C3·C4는 [추정]에서 **[확정 재현]**으로 바뀌었다. 추가로, `main`의 INSERT에는 `updated_at`이 빠져 있어서 IGNORE가 이를 `0000-00-00`으로 채운다. 실환경 기존 행도 같을 가능성이 높다. RDS `sql_mode`와 실제 데이터는 확인하지 않았다.

**L2: DB 장애 주입 (`run-scenario.sh`)**

| 시나리오 | 이미지 | 발행 | DB 행 | 유실 | 중복 행 | DLQ | nack (db_failure) | 파티션 재할당 | poll 간격 초과 |
|---|---|---|---|---|---|---|---|---|---|
| `none` | 브랜치 | 54,000 | 54,000 | 0 | 0 | 0 | 0 | 0 | 0 |
| `reject 150` | 브랜치 | 54,000 | 54,000 | **0** | 0 | 0 | 60 | 0 | 0 |
| `drop 150` | 브랜치 | 54,000 | 54,000 | **0** | 0 | 0 | 60 | 0 | 0 |
| `reject 150` + 재기동(`RESTART_AT=60`) | 브랜치 | 54,000 | 54,000 | 0 | 0 | 0 | 20 | 10 | 0 |
| `reject 150` (대조군) | `main` | 54,000 | 53,940 | **60** | 0 | 60 | — | 0 | 0 |

- 같은 날 같은 조건에서 `main`은 2026-10-01 재현과 같은 60건 유실(전부 `dlq_message`에만 남음)을, 브랜치는 유실 0을 보였다.
- 첫 `main` 대조 실행은 도중에 호스트(Mac)가 10분간 잠자기에 들어가 결과를 폐기했다(17:47~17:57). 시계가 건너뛰면서 poll 간격 초과와 그룹 재참여가 일어나 비교할 수 없는 조건이 됐다. 재실행은 `caffeinate -i`로 잠자기를 막았다. 브랜치 4개 실행은 잠자기 전에 끝났다.
- 이번 조건(200건/s, 배치 최대 100건)에서는 D4(poll 간격 초과)가 나타나지 않았다. 배치가 크고 장애가 긴 조건은 확인하지 않았다.

**L3: DB 장애 중 DLQ 저장 실패 (`reject` 60s)**

정상 100건으로 예열한 뒤 장애를 주입했다. 장애 중에 불량만 담긴 메시지 10건을 보내고, 이어서 정상 100건과 불량 10건을 섞어 보냈다.

| 항목 | 결과 |
|---|---|
| 장애 중 (30s 시점) | lag 120, `dlq_message` 0, 참여 이력 100 → ACK되지 않고 대기 |
| 복구 후 참여 이력 | 200 / 기대 200, 누락 0 |
| 복구 후 `dlq_message` | 20행, 원본 key 20개 / 기대 20, 누락 0, **중복 기록 없음** |
| nack | `db_failure` 20, `dlq_store_failure` 6 |
| 파티션 재할당 | 0 |

이번 조건에서는 DB가 완전히 끊긴 상태라 DLQ 저장이 일부만 성공하는 일이 없어서 Q4(DLQ 기록 중복)가 나타나지 않았다. DB가 오락가락하는 조건에서는 여전히 생길 수 있다.

### 8.6 남은 검증

- 건별 처리 도중 DB 장애가 시작되는 경우 (실측). 단위 테스트로만 확인했다.
- 배치가 크고(500) 장애가 긴 조건에서 poll 간격 초과(D4)
- DB 읽기 전용·권한 오류(D6)에서 DLQ로 보내지 않고 재시도하는지
- DLQ 기록 중복(Q4)과 Replay 배치 연동 → 배치 작업
- 실환경 하네스 (RDS·3 브로커·ASG): 성능 전후 비교 포함. 비용 발생, 사용자 승인 필요
