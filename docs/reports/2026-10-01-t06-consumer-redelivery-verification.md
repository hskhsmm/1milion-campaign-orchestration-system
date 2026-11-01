# 2026-10-01 T06 — RDS 장애 시 Consumer 재처리 보장 여부 검증

> 상태: **로컬 재현 완료 — 리밸런싱 없는 DB 장애에서 유실 재현 [확정]** (§6)
> 기준 시각: KST. 기준 커밋: `bc294b4` (main, 2026-09-25)
> 근거 등급: **[확정]** 코드/git/원본 이미지로 확인 / **[추정]** 코드에서 추론, 실행 확인 안 됨 / **[미확정]** 증거 없음
>
> **작성 이후 변경 (2026-11-01)**: §10.1 코드 수정을 `fix/consumer-dlq-retry-boundary`에서 진행했다. DB 장애·DLQ 저장 실패 시 배치 전체를 `nack(0)`으로 다시 받고, 중복만 무시하는 INSERT로 바꿨다. `repro/t06` 재검증에서 `reject 150`·`drop 150`·재기동 모두 유실 0, 같은 날 `main` 대조는 60건 유실을 확인했다(로컬 단일 브로커 기준). 실환경 검증은 하지 않았다. 이 문서 본문의 코드 설명은 `bc294b4` 기준이다. 현재 상태는 [DLQ 실패 시나리오](2026-11-01-dlq-failure-scenarios.md)를 본다. 본문에 나오는 `docs/current/…`, `repro/t06/` 경로는 작성 당시의 로컬 미추적 파일이다.

## 요약

- **[확정, 로컬 재현]** 현재 코드(`bc294b4`)에서 리밸런싱이 없는 150초 DB 장애 시 54,000건 중 **60건이 `participation_history`에서 유실**됐다. 실패 배치는 ack를 보류했지만 Kafka 재전달은 일어나지 않았고, 다음 배치 ack가 실패 건을 넘어 커밋했다. 연결 거부(REJECT)와 무응답(DROP) 두 방식 모두 결과가 같았다(§6).
- 유실된 60건은 모두 `dlq_message`(PENDING)와 DLQ 토픽에 남았다. 수동 DLQ Replay로 복구할 수는 있지만 **자동 재처리는 아니다**.
- **[확정, 로컬 재현]** 같은 장애 중에 앱을 정지했다가 복구 후 재기동하면(인스턴스 교체 모사) **유실 0**이었다. 커밋 오프셋이 실패 배치 앞에 멈춰 있어서, 재기동한 Consumer가 그 위치부터 다시 소비했다. → "T06 자동 재처리"는 H1(인스턴스 교체에 의한 파티션 재할당)이었다면 설명된다. 다만 T06에서 실제로 교체가 일어났는지는 여전히 미확정이다(§7).
- 인계 문서의 "코드에서 확인된 사실" 1~5는 현재 코드와 일치한다 [확정]. 단, DLQ 경로 설명 1건은 보완이 필요하다(§2.5).
- T06은 **2026-05-05 17:52~17:55경** 실행됐다 [확정: README 이미지 축 시각, 블로그 스크린샷 파일명 17:57].
  ack 보류 코드(`bab078e`, 14:39)가 들어간 **이후**, `remaining==0` 수정(`0d8126d`, 18:34) **이전**이다.
- T06 당시 prod 설정에는 `management.health.db.enabled`가 **없었다** → DB 헬스 포함 상태였다 [확정]. `false`로 바뀐 건 2026-06-01(`8da9d13`).
- 블로그가 근거로 든 "Kafka lag 15K 폭증 → 복구 후 빠르게 해소"는 **재처리의 증거가 되지 못한다**. lag은 커밋 오프셋 기준이라 유실 시나리오에서도 똑같이 보인다(§2.4).
- 새 가설 H4(`max.poll.interval` 초과 → 그룹 이탈 → 리밸런싱)를 추가했다(§3).
- T06 이후 약 5개월이 지나 당시 ASG 이력·앱 로그·관련 지표는 남아 있지 않을 가능성이 높다(§4). 사후 확정보다 재현 테스트로 판단하는 편이 현실적이다.

---

## 1. 인계 문서 사실 재확인

| # | 항목 | 결과 | 위치 |
|---|---|---|---|
| 1 | 배치 리스너, `AckMode.MANUAL`, enable.auto.commit=false | [확정] 일치 | `app/campaign-core/src/main/java/io/eventdriven/campaign/config/KafkaConfig.java:111,126,132` |
| 1 | max-poll-records 100 / max-poll-interval 600000 / session-timeout 45000 | [확정] 일치 | `application-prod.yml:83-85` |
| 1 | ErrorHandler / BackOff / seek / nack / RebalanceListener 없음 | [확정] main 소스에 없음 (`ErrorHandler` grep 결과는 Spring Batch Job 설정뿐) | — |
| 1 | concurrency = 토픽 파티션 수(prod 10) | [확정] 인스턴스당 10 스레드 | `KafkaConfig.java:129-130` |
| 2 | batchUpdate 실패 → 건별 insert → 실패 시 DLQ+Slack, 실패 1건이라도 있으면 ack 생략 후 정상 리턴 | [확정] 일치 | `ParticipationEventConsumer.java:61-124` |
| 3 | Hikari pool 20 / min-idle 10 / connection-timeout 20000, socketTimeout 없음 | [확정] 일치 | `application-prod.yml:27-32` |
| 4 | `publishAndStore()` @Transactional, 저장·발행 각각 try-catch | [확정] 일치 — 단 §2.5 참고 | `DlqMessageService.java:74-133` |
| 5 | ASG `health_check_type = "ELB"`, grace 180 / ALB `/actuator/health` 30s·timeout 5s·unhealthy 2 | [확정] 일치 (T06 시점 `426ef65`에도 동일) | `infra/asg.tf:47-48`, `infra/alb.tf:25-35` |
| 5 | 현재 prod `management.health.db.enabled=false` | [확정] 일치 | `application-prod.yml:11-13` |

## 2. 인계 문서에 없던 사실

### 2.1 T06 실행 시점과 코드 버전 [확정]

| 시각 (2026-05-05) | 이벤트 | 근거 |
|---|---|---|
| 03:31 | CLAUDE.md에 T06 "미완료" | `7a8c151` |
| 14:39 / 14:51 | ack 보류 코드 커밋 / PR #81 머지 | `bab078e`, `426ef65` |
| 17:52~17:55 | 5xx 발생 구간 (장애 주입) | `docs/images/t06-5xx-recovery.png` 축 |
| 17:57 | T06 스크린샷 (TPS 급락, lag 15K) | 블로그 이미지 파일명 |
| 18:34 | remaining==0 try-catch 수정 | `0d8126d` |
| 19:01~19:33 | T07 (ASG terminate) | 이미지 파일명, `d62c691` |

- PR #81 머지가 실제 배포됐는지는 [미확정]. CodeDeploy 이력으로 확인할 수 있으나 보존 기간이 지났을 수 있다.
- T06 당시 Consumer(`bab078e`)는 현재 코드와 다르다:
  - DLQ 전송이 건별 insert **루프 안**에서 즉시 실행됐다(현재는 루프 후 일괄).
  - `consumer.db.transient.failures`, `consumer.db.committed` 지표는 **아직 없었다**(`f1a4f5c`, 2026-06-09 추가). → 인계 문서 검증 #3은 T06 구간에 대해 불가능하다.

### 2.2 T06 당시 헬스체크에 DB 포함 [확정]

`git show bab078e:.../application-prod.yml`의 `management.health`에는 `kafka.enabled: false`만 있고 `db` 항목이 없다. Spring Boot 기본값으로 DB 헬스가 포함된다. README의 "health에 DB 포함 → ALB unhealthy" 기록과 맞는다.
ALB가 실제로 타깃을 unhealthy로 판정했는지, ASG가 인스턴스를 교체했는지는 [미확정].

### 2.3 장애 구간 길이 [추정]

5xx 그래프 기준으로 약 17:52:30~17:55, 대략 **2.5분**이다. README의 "약 1분"보다 길다. 다만 SG 복구 후 커넥션 풀 회복과 ALB healthy 전환(healthy_threshold 5 × 30s = 150s)까지 포함한 값일 수 있어, 실제 SG 차단 시간은 알 수 없다.
ALB unhealthy 판정에 필요한 시간(unhealthy_threshold 2 × interval 30s ≈ 60s)보다는 길다.

### 2.4 Kafka lag은 재처리 증거가 아니다 [확정: 정의상]

Consumer lag = log-end-offset − **committed offset**. ack를 보류하면 fetch position과 무관하게 committed가 멈추므로 lag이 커진다.

| 시나리오 | 장애 중 lag | 복구 후 lag |
|---|---|---|
| 재처리됨 (리밸런싱 → committed부터 재소비) | 증가 | 재소비하면서 감소 |
| 유실됨 (position은 이미 전진, 다음 배치 ack가 실패 건을 넘겨 커밋) | 증가 | **첫 성공 ack 시점에 즉시 감소** |

블로그의 "lag 15K 폭증 → 빠르게 해소" 관측은 두 시나리오 모두에 들어맞아 둘을 구분하지 못한다.

### 2.5 DB 장애 중에는 DLQ 토픽 발행도 일어나지 않을 수 있다 [추정 — 로컬 재현 조건에서는 발생하지 않음, §6.3]

인계 문서에는 "dlq_message 저장 후 DLQ 토픽 발행 (각각 try-catch)"라고 되어 있다. 그러나 `publishAndStore()`는 `@Transactional`(JPA 트랜잭션 매니저)이다. 트랜잭션 **시작 시점**에 커넥션을 잡으므로, DB 장애 중이면 메서드 본문에 들어가기 전에 `CannotCreateTransactionException`이 날 가능성이 높다.

- 그렇다면 내부 try-catch 두 개는 모두 실행되지 않는다 → **dlq_message 저장도 DLQ 토픽 발행도 없음**.
- 이 경우 남는 로그는 `Failed to persist DLQ message`가 아니라 Consumer 쪽 `Failed to publish consumer DLQ payload`이다(`ParticipationEventConsumer.java:200`).
- 건별 실패마다 커넥션 대기(최대 20s)가 insert와 DLQ에서 각각 발생한다.

→ 재현 테스트에서 위 로그로 확인할 항목에 추가한다.

### 2.6 문서 간 불일치 [확정]

| 문서 | T06 diff 기록 |
|---|---|
| `README.md:445-455` | diff 값 없음 |
| `docs/blog/code-deep-dive.md:857` | "diff=0 확인" |
| `docs/archive/dev-notes/CLAUDE.md:335` | 증거 폴더 `장애 테스트/T06/` = "정합성 diff=0" |

- 증거 폴더(`3-broker clustering 이후- 테스트 사진/장애 테스트/T06/`)는 로컬 저장소에 없다. diff=0 근거 이미지가 실제로 있는지 사용자 확인이 필요하다.
- 만약 diff=0이 실제로 측정됐다면, H3(실패 없음) 또는 리밸런싱 계열(H1/H4)을 뒷받침한다. 이 경우 H2만으로는 설명하기 어렵다.

## 3. 가설 (갱신)

| ID | 내용 | T06 조건과의 정합성 | 판정 근거 |
|---|---|---|---|
| H1 | DB 헬스 포함 → ALB unhealthy → ASG(ELB) 인스턴스 교체 → 리밸런싱 | 당시 DB 헬스 포함 [확정], 장애 구간 ≈ 2.5분 > 판정 60s [추정]. 모든 인스턴스가 동시에 unhealthy가 됐을 것 | ASG Activity / 인스턴스 ID 변경 |
| H2 | 실패 배치가 파티션 마지막 메시지 → 후속 ack 없음 → 이후 재시작 시 재처리 | 부하 중(4.6K req/s)에 장애가 일어나 후속 메시지가 계속 있었을 가능성이 높다. 파티션 10개 전부에서 이 조건이 성립하긴 어렵다 | 파티션별 log-end vs 실패 offset |
| H3 | 레코드 단위 실패 자체가 없었음 (SG stateful → 기존 커넥션 유지) | 5xx 발생과는 충돌하지 않는다(5xx 원인은 API 경로의 remaining==0 DB 호출과 ALB unhealthy). Consumer 실패 여부와는 별개 | `Insert failed (transient)` 로그 유무 |
| **H4 (신규)** | DB 응답이 멈춰 한 배치 처리 시간이 `max.poll.interval`(600s) 초과 → Consumer 그룹 이탈 → 리밸런싱 → committed부터 재처리 | socketTimeout이 없어 끊긴 TCP에서 쿼리가 OS 재전송 한계(리눅스 기본 약 15분)까지 블록될 수 있다 [추정]. 건별 폴백이 건당 최대 20s+20s 대기 × 100건이다 [추정]. 다만 장애가 약 2.5분에 끝났다면 600s를 넘기기 어렵다 | Kafka client 로그 `consumer poll timeout has expired`, `Revoke/Assigned partitions` |

## 4. 사후 검증 가능성 (T06 + 약 149일)

| 데이터 | 보존 기간 (AWS/도구 기본값) | T06 데이터 잔존 |
|---|---|---|
| ASG Activity History | 6주 | [추정] 소멸 |
| CloudTrail Event history | 90일 | [추정] 소멸 |
| CodeDeploy 배포 이력 | — | 조회 필요 |
| 앱 로그 | `aws_cloudwatch_log_group` 정의 없음 → 인스턴스 로컬. T07 등으로 인스턴스 교체 | [추정] 소멸 |
| Prometheus | 보존 설정 없음 → 기본 15일 | [추정] 소멸 |
| CloudWatch 커스텀 지표 (`CampaignSystem/V2`) | 1시간 해상도 455일 | Consumer 지표는 당시 없었음 (§2.1) |
| ASG 그룹 지표 (`GroupInServiceInstances` 등) | 1시간 해상도 455일 | 당시 활성화 여부 [미확정] (`enabled_metrics`가 Terraform에 들어온 건 2026-08-02 `536e0af`, 드리프트 정리). 1시간 해상도라 17시대(T06)와 19시대(T07)는 구분할 수 있다 |
| RDS `participation_history` | DB가 유지됐다면 남아 있음 | T06 캠페인 ID를 알면 `total_stock` 대비 건수 조회 가능 (Redis 발급 수는 소멸했을 가능성이 높아, 판매 수량 = total_stock 기준으로 대체) |

→ 인계 문서의 검증 1~3은 사후로 수행하기 어려울 가능성이 높다. 4(diff)는 DB와 캠페인 ID가 남아 있으면 가능하다.

## 5. 다음 단계 (작성 당시 계획 — 진행 결과는 §6~§9, 남은 작업은 §10)

1. 사후로 확인할 수 있는 것부터 확인 (사용자 실행 필요)
   - T06 캠페인 ID 확인 → `participation_history` 건수 vs `total_stock`
   - CloudWatch `AWS/AutoScaling` `GroupInServiceInstances`/`GroupTerminatingInstances`, 2026-05-05 08:00~09:00 UTC, 1시간 해상도 조회
   - `장애 테스트/T06/` 증거 폴더에 diff 이미지가 있는지 확인
2. 로컬 재현 테스트 설계 (Docker Compose + toxiproxy 또는 `docker pause mysql`)
   - 리밸런싱이 없는 조건에서 유실이 일어나는지 확인. 기대: 유실 발생
   - §2.5(DLQ 경로 동작)와 H4(poll interval 초과)도 같은 환경에서 확인
3. AWS 재현 테스트 (NACL 차단 또는 RDS reboot) — 로컬 결과를 보고 필요하면 진행
4. 수정안(A: nack / B: DefaultErrorHandler) 결정과 문서 정정은 재현 결과를 본 뒤에 진행

---

## 6. 로컬 재현 결과 (2026-10-01)

### 6.1 환경

| 항목 | 값 |
|---|---|
| 앱 | `bc294b4` 커밋 소스를 `git archive`로 추출해 빌드 (`campaign-core:t06-repro-bc294b4`). 작업 트리의 미커밋 변경은 제외 |
| 프로필 | `local` + 환경변수 오버라이드. `application-local-test.yml`은 미추적 파일이라 이미지에 없다 |
| Consumer | max-poll-records 100 / max-poll-interval 600000 / session-timeout 45000 (prod 값), concurrency 10 |
| Hikari | pool 20, min-idle 10, connection-timeout 20000 (prod와 동일), socketTimeout 없음 |
| Kafka | 단일 브로커, 토픽 10 파티션, DLQ 토픽 3 파티션 |
| 부하 | `ParticipationEvent`를 토픽에 직접 발행. sequence = userId = key = 1..N, 초당 200건 |
| 장애 주입 | mysql 네트워크 네임스페이스를 공유하는 사이드카에서 iptables로 3306 인바운드 차단 |
| 스크립트 | `app/campaign-core/repro/t06/` (`run-scenario.sh`, `docker-compose.yml`, `scripts/produce.sh`) |
| 원본 결과 | `app/campaign-core/repro/t06/results/<mode>-<timestamp>/` |

시나리오마다 `docker compose down -v`로 환경을 새로 만들었다. 진행은 장애 전 30초 → 장애 150초 → 복구 후 90초 발행 → lag 0 대기 순이다.

### 6.2 결과

| 항목 | none (대조군) | S1 reject (TCP RST) | S2 drop (무응답) |
|---|---|---|---|
| 조건 | 장애 없음, 40초 | 150초 | 150초 |
| 발행 N | 8,000 | 54,000 | 54,000 |
| DB 건수 | 8,000 | 53,940 | 53,940 |
| **유실** | **0** | **60** | **60** |
| 유실 건 중 dlq_message에 있는 건 | — | 60 | 60 |
| dlq_message / DLQ 토픽 | 0 / 0 | 60 / 60 | 60 / 60 |
| 최종 lag | 0 | 0 | 0 |
| Batch insert failed | 0 | 10 | 10 |
| Insert failed (transient) | 0 | 60 | 60 |
| Skipping ack | 0 | 10 | 10 |
| 장애 이후 partitions assigned / revoked | 0 / 0 | **0 / 0** | **0 / 0** |
| poll timeout expired | 0 | 0 | 0 |
| 유실 sequence 범위 | — | 6201~6297 사이 60건 | S1과 동일 |

- 대조군의 assigned 15 / revoked 5는 앱 기동 시 10개 스레드가 합류하며 생긴 초기 리밸런싱이다. 이후 집계는 앱 준비 시점 이후 로그만 센다.
- S1과 S2의 유실 목록이 같은 이유: 두 시나리오 모두 30초 시점(sequence ≈ 6,200)에 장애를 넣었고, key 기반 파티셔닝과 실패 패턴(아래)이 결정적이기 때문이다.

### 6.3 관찰된 동작 (S1 로그 기준, S2도 동일)

```
10:33:16  장애 주입
10:33:36  10개 스레드 전부 "Batch insert failed" (커넥션 획득 20s 타임아웃) → 건별 폴백
          건별 insert: 건마다 커넥션 획득 20s 대기 후 실패 → 6건 실패에 ≈ 120s
10:35:47  장애 해제
10:35:52  각 스레드: 나머지 건 성공, "transientFailures=6" → "Skipping ack" (latencyMs ≈ 155,400)
          DLQ 전송 60건 (루프 종료 후 일괄) → 이미 DB 복구됨 → dlq_message 저장 성공
10:35:52  다음 poll: 실패 건이 아니라 **그다음 레코드 100건**을 받음 → 전부 성공 → ack
          → committed offset이 실패 건을 넘어감 → 실패 60건은 다시 오지 않음
```

- **인계 문서의 메커니즘 분석이 맞다 [확정]**: ack 생략은 fetch position을 되돌리지 않는다. 다음 배치 ack로 실패 건을 넘어 커밋된다.
- **H4 (poll interval 초과 → 리밸런싱)는 이 조건에서 발생하지 않았다**: 실패 배치 처리 시간은 약 155s로 600s보다 짧다. 배치당 실패 건수가 많거나(건당 20s) 장애가 더 길면 600s를 넘을 수 있지만, 이번에는 검증하지 않았다.
- **S2(DROP)가 S1과 같았던 이유**: 유휴 커넥션은 Hikari 검증(`Failed to validate connection`, 약 5s)에서 폐기됐고, 새 커넥션은 획득 타임아웃(20s)으로 실패했다. 차단 순간에 진행 중인 쿼리가 없어서, socketTimeout 부재로 인한 무한 대기 경로는 타지 않았다.
- **§2.5 (DLQ 저장 실패)는 이 조건에서 발생하지 않았다**: 현재 코드는 DLQ 전송을 건별 루프가 **끝난 뒤** 한다. 건별 실패가 건당 20s씩 걸려 루프가 장애 구간을 넘겼고, DLQ 전송 시점에는 DB가 복구돼 있었다. `CannotCreateTransactionException` 60건은 `insertSuccess`(JPA 트랜잭션)에서 났다. 배치 건수 × 20s보다 장애가 길면 DLQ 전송도 장애 중에 일어나 저장에 실패할 수 있으나, 미검증이다.

### 6.4 T06 해석에 대한 함의

- 현재 코드와 같은 흐름이면, 리밸런싱 없이 DB 장애가 나면 유실이 생긴다. 다만 DLQ(dlq_message)에 남아 수동 Replay로 복구할 수 있는 상태다(이번 조건 기준).
- T06 당시 코드(`bab078e`)는 DLQ 전송이 루프 **안**에 있었다 → 장애 중에 DLQ를 시도했으므로 저장에 실패했을 가능성이 이번 재현보다 높다 [추정].
- T06에서 "자동 재처리"가 실제로 일어났다면, 리밸런싱 계열(H1: 헬스체크 DB 포함 → 인스턴스 교체)이 가장 유력하다. 사후 증거로는 여전히 확정할 수 없다(§4).

### 6.5 남은 확인 후보 (미진행)

- ~~리밸런싱이 있는 조건에서 유실 0이 되는지 확인~~ → §7에서 완료
- 장애 중 DLQ 저장 실패 조건(장애 시간 > 배치 건수 × 20s)
- 수정안 A(nack) / B(DefaultErrorHandler) 적용 후 같은 시나리오 재실행

---

## 7. 리밸런싱 검증 — 장애 중 앱 재기동 (H1 모사, 2026-10-01)

### 7.1 조건

- S1과 동일: REJECT, 150초, 200/s, 장애 전 30초 / 복구 후 90초
- `RESTART_AT=60`: 장애 시작 60초 후 `docker stop t06-app`. ALB unhealthy 판정(2 × 30s ≈ 60s) 뒤 ASG가 인스턴스를 종료하는 것을 모사했다.
- 장애 해제 직후 `docker start t06-app`. 교체 인스턴스가 DB 복구 뒤 기동되는 것을 모사했다.
- 실행: `RESTART_AT=60 ./run-scenario.sh reject 150 200 30 90` → `results/reject-restart60-20261001-104716/`

### 7.2 결과

| 항목 | S1 (재기동 없음) | **S1 + 재기동** |
|---|---|---|
| 발행 N | 54,000 | 54,000 |
| DB 건수 | 53,940 | **54,000** |
| **유실** | 60 | **0** |
| dlq_message / DLQ 토픽 | 60 / 60 | 0 / 0 |
| Insert failed (transient) | 60 | 20 (정지 전 스레드당 2건) |
| Skipping ack | 10 | 0 (실패 배치가 끝나기 전에 정지) |
| 완료된 배치의 polled 합계 | — | 54,000 |
| 장애 이후 partitions assigned | 0 | 10 (재기동) |

타임라인:

```
10:48:18  장애 주입
10:49:18  각 스레드 건별 insert 2번째 실패 (20s 타임아웃 × 3: 배치 1회 + 건별 2회)
10:49:19  docker stop → 1초 내 종료. 실패 배치는 끝나지 않았고 ack·DLQ 전송 없음
10:50:51  장애 해제
10:50:57  앱 기동 완료, 10개 파티션 할당 → committed offset부터 소비 재개
10:52:34  lag 0, DB 54,000건
```

정지 직전과 장애 종료 시점의 커밋 오프셋은 같다(파티션당 587~670). 즉 실패 배치의 시작 위치에서 멈춰 있었다. 재기동 후 그 위치부터 다시 받아 전부 저장했다. 정지 시점에 실패한 20건은 재소비 때 정상 저장됐다(INSERT IGNORE라 중복 위험도 없다).

### 7.3 해석

- **확인된 것 [확정]**: 실패 배치가 ack되기 전에 파티션 재할당(재기동)이 일어나면 committed offset부터 재소비되어 유실이 없다. "자동 재처리"는 **ack 보류 자체가 아니라, ack 보류 + 파티션 재할당의 조합**으로 일어난다.
- **필요 조건**: 재할당이 실패 배치 다음 ack보다 **먼저** 일어나야 한다. S1처럼 같은 프로세스가 복구 후 계속 돌면 다음 배치 ack가 실패 건을 넘어 커밋한다.
- **T06과의 관계**: T06 당시 DB 헬스가 포함돼 있었고(§2.2), 장애 구간이 약 2.5분이었다(§2.3). 그래서 ALB unhealthy → ASG 교체가 일어날 조건은 갖춰져 있었다. 실제로 일어났다면 T06의 "재처리 성공" 관측과 맞는다. 하지만 실제 교체 여부는 증거가 없어 [미확정]이다(§4).
- **역설**: T06의 재처리는 "헬스체크에 DB가 포함된 구조적 허점"(README 허점 ②) 덕분이었을 가능성이 있다. 이후(2026-06-01) DB 헬스를 제외했으므로, **현재 prod에서는 같은 장애가 나도 인스턴스가 교체되지 않아 §6의 유실 경로를 탈 가능성이 높다** [추정].

### 7.4 이번 검증에서 다루지 않은 것

- 재기동 시점에 DB가 아직 장애 중인 경우: 교체 인스턴스 기동 시 Flyway/JPA 초기화가 실패할 수 있다. 이번에는 복구 후 기동만 확인했다.
- 종료 방식: `docker stop`이 1초 안에 끝났고 graceful shutdown 로그는 남지 않았다. 재기동이 정지 90초 뒤라 session timeout(45s)과 무관하게 재할당됐다. 짧은 간격으로 재기동하는 경우는 확인하지 않았다.
- prod `auto-offset-reset`은 기본값 `latest`이고 로컬은 `earliest`다. 이번 시나리오는 모든 파티션에 커밋 오프셋이 있어 영향이 없다.

---

## 8. unhealthy 판정 시 인스턴스 교체 여부 (인프라 코드 확인, 2026-10-01)

### 8.1 설정 사실 [확정: Terraform 코드 / tfstate]

| 항목 | 값 | 근거 |
|---|---|---|
| ASG `health_check_type` | `ELB` (T06 시점 `426ef65`에도 동일) | `infra/asg.tf:47` |
| `health_check_grace_period` | 180s — **인스턴스 기동 직후에만** 적용 | `infra/asg.tf:48` |
| `suspended_processes` | 없음 (HealthCheck / ReplaceUnhealthy 활성) | `infra/terraform.tfstate` |
| min / desired / max | 2 / 2 / 3 | `infra/asg.tf:32-34` |
| TG 헬스체크 | `/actuator/health`, 30s 간격, timeout 5s, unhealthy 2회 | `infra/alb.tf:25-35` |
| TG `deregistration_delay` | 미설정 → **기본 300s** (tfstate 값 300) | `infra/alb.tf`, tfstate |
| CodeDeploy | 배포 그룹이 ASG에 연결 → 새 인스턴스 기동 시 자동 배포 | `infra/codedeploy.tf:42` |
| 앱 헬스에 DB 포함 | T06 당시 포함 / **2026-06-01부터 제외** | §2.2 |

### 8.2 동작 (AWS 동작 기준, 이 저장소 코드가 아님)

ASG가 `ELB` 헬스체크 타입이면, 대상 그룹이 unhealthy로 판정한 인스턴스를 ASG도 Unhealthy로 표시하고 교체한다(종료 → 새 인스턴스 기동). 그래서:

- **T06 당시**: DB 장애 → `/actuator/health` DOWN(503) → 약 30~60s 뒤 TG unhealthy → ASG 교체 대상. **교체는 일어날 수 있는 구성이었다** [확정: 설정] / 실제 발생 여부 [미확정].
- **현재 prod**: DB 헬스 제외 → DB 장애 중에도 health 200 → **교체 안 됨** [추정: 설정 기반]. 그래서 §6의 유실 경로가 그대로 남는다.

### 8.3 로컬 재현(§7)이 낙관적인 조건인 이유 [추정, 미검증]

§7은 장애 60초 시점에 프로세스를 즉시 종료했다. 실제 교체에서는 그 전에 다음 단계가 끼어든다.

1. ASG가 종료할 인스턴스를 TG에서 등록 해제 → **연결 드레이닝**(`deregistration_delay` 최대 300s). 진행 중인 요청과 연결이 없으면 더 빨리 끝난다. k6 keep-alive 연결이 있으면 오래 걸릴 수 있다.
2. 드레이닝이 끝나는 동안 **앱(Consumer)은 계속 돈다**.
3. 그 사이 DB가 복구되고 Consumer가 다음 배치를 ack하면, 실패 건을 넘어 커밋된다 → 이후 인스턴스가 종료돼도 재처리되지 않는다.

즉 H1이 T06 결과를 설명하려면 **"인스턴스 종료(Consumer 이탈)가 DB 복구 후 첫 ack보다 먼저"** 일어나야 한다. T06 장애는 약 2.5분이었다. unhealthy 판정(≈60s) + 드레이닝(0~300s)을 더하면 종료가 복구 뒤로 밀렸을 수 있다. 이 경우 T06에서도 유실이 났을 수 있다.

### 8.4 추가 리스크 (참고)

- **DB 장애 중 교체 인스턴스 기동**: Flyway가 기동 시 DB에 접속하므로 앱 기동에 실패할 수 있다. 그러면 grace 180s 뒤 다시 unhealthy → 교체가 반복될 수 있다.
- **모든 인스턴스가 동시에 unhealthy**: ALB는 모든 타깃이 unhealthy면 fail-open으로 전부에 라우팅한다. ASG가 여러 대를 동시에 교체하는지(교체 순서·동시성)는 AWS 동작 상세 확인이 필요하다.
- **구버전 컨테이너 자동 기동**: AMI에 구버전 앱 컨테이너가 남아 있어 새 인스턴스가 뜰 때 함께 기동된다(`2026-09-28-150m-reproduction-set-removal-trace.md`). 교체가 일어나면 이 문제도 같이 발생한다.

---

## 9. 결론 (사용자 제공 사실 반영, 2026-10-01)

### 9.1 추가된 사실 (사용자 확인)

- T06 캠페인: `total_stock` − `participation_history` 건수 **diff = 0** → T06에서 유실 없음
- T06 중 ALB 대상 **unhealthy 판정 발생**

### 9.2 판단

| 질문 | 판단 | 근거 |
|---|---|---|
| ack 보류만으로 재처리되나? | **아니다 [확정]** | §6 로컬 재현: 리밸런싱 없으면 60건 유실 |
| T06 재처리 메커니즘은 파티션 재할당인가? | **그렇다 [강한 정황]** | diff=0 + §6/§7 재현: 실패 건이 살아남는 경로는 ack 전 재할당뿐이다 |
| 재할당의 트리거가 unhealthy → ASG 교체(H1)인가? | **가장 유력 [미확정]** | unhealthy 발생(사용자) + ELB 헬스체크 타입·교체 프로세스 활성(§8). ASG Activity 소멸로 교체 기록은 직접 확인 불가 |
| H3 (Consumer 실패 자체가 없었음) | **약화** | unhealthy = DB 헬스체크 실패 = 같은 Hikari 풀이 DB에 접근하지 못함. 같은 풀을 쓰는 Consumer도 실패했을 가능성이 높다 |
| H4 (poll interval 초과) | 배제 불가, 가능성 낮음 | 결과는 똑같이 재할당이라 diff로는 구분할 수 없다. 장애 약 2.5분이라 600s 초과는 어렵다 |
| §8.3 드레이닝 지연 우려 | T06에서는 문제가 되지 않았을 가능성 | 부하가 17:55경 끝나(TPS 0) 진행 중 요청이 없어 드레이닝이 빨리 끝났을 수 있다 [추정] |

- **ack 보류 코드의 역할**: 필요조건이었다. `bab078e` 이전처럼 항상 ack했다면, 재할당이 일어났어도 실패 건이 이미 커밋돼 유실됐을 것이다. 다만 **충분조건은 아니다**. 재할당(인스턴스 교체)이 함께 일어나야 재처리된다.
- **현재 prod**: 2026-06-01에 DB 헬스를 제외해 unhealthy → 교체 경로가 사라졌다. 같은 장애가 나면 §6처럼 유실될 가능성이 높다 [추정, §6 재현 근거].

### 9.3 미해결

- T06 앱 5xx 약 1.5K/s의 출처: Grafana 패널은 앱 자체 `http_server_requests` 5xx를 센다(`campaign.json`). 그런데 T06 당시 참여 API의 DB 호출은 `remaining==0`인 단 1건뿐이었다(`0d8126d^` ParticipationService). 그래서 코드상으로는 초당 1.5K건을 설명할 수 없다. 원인은 미확인이다.

---

## 10. 후속 작업 (미착수)

> 재현 환경: `app/campaign-core/repro/t06/` — 수정 후 같은 시나리오로 재검증할 수 있다.

### 10.1 코드 수정 — Consumer 재처리 보장

- [ ] 수정 방식 결정 (§0 인계 문서 수정 후보)
  - A. 실패 시 `acknowledgment.nack(index, Duration)` → 실패 지점으로 seek 후 재시도 (spring-kafka 4.x 배치 nack 동작 확인 필요)
  - B. 예외를 던지고 `DefaultErrorHandler`(BackOff + `DeadLetterPublishingRecoverer`)로 처리
- [ ] 같이 정할 것
  - 일시적 실패(재시도)와 영구 실패(DLQ)의 경계. 현재는 일시적 실패에도 DLQ 전송 + ack 보류를 동시에 해서 이중 경로가 된다
  - 재시도 상한 / 백오프. 무한 재시도면 장시간 장애 시 파티션 전체가 정지한다
  - 건별 폴백 비용: 건당 커넥션 대기 20s × 배치 100건 → `max.poll.interval`(600s) 초과로 그룹 이탈할 수 있다(H4). DB 연결 실패면 건별 폴백 없이 배치 전체를 재시도하는 편이 낫다
  - DB 장애 중 DLQ 저장 경로: `publishAndStore()`가 DB 트랜잭션에 의존한다(§2.5)
  - JDBC `socketTimeout` 설정 여부 (무응답 시 무한 대기 방지)
- [ ] 코드 주석/로그 정정: `ParticipationEventConsumer.java:118`, `:122` ("Kafka will redeliver after recovery")
- [ ] 재검증: `./run-scenario.sh reject 150` / `drop 150` (재기동 없음) → **유실 0**, 재기동 시나리오(`RESTART_AT=60`)도 유실 0·중복 0
- [ ] 장애가 긴 경우(예: 600s 이상) 동작 확인 → 재시도 상한·DLQ 전환이 의도대로인지

### 10.2 문서 정정 — "ack 보류 → 자동 재전달" 표현 및 T06 기록

| 파일 | 위치 | 정정 내용 |
|---|---|---|
| `README.md` | 445~455 (T06), 578 (지표 설명) | 재처리는 ack 보류 + 파티션 재할당(unhealthy → ASG 교체 추정)의 결과. diff=0 기록 추가. 현재(DB 헬스 제외 후)는 같은 장애 시 유실 가능 → 수정 전까지 알려진 한계로 명시 |
| `ARCHITECTURE.md` | 98, 465~469, 780 | "ack 보류 → Kafka 자동 재전달" 정정 |
| `docs/blog/part2-150만-트래픽-정합성.md` | T06 절 (546~) | "lag 해소 = 재전달" 근거 정정(§2.4) |
| `docs/blog/code-deep-dive.md` | 632, 857, 1583 | 동일 |
| `docs/archive/project-deep-dive-2026-05.md` | 298, 461~, 578 | 동일 (archive라 정정 여부 판단 필요) |
| `docs/archive/dev-notes/CLAUDE.md` | 384 | 동일 (archive) |

### 10.3 기타

- [ ] `repro/t06/` 스크립트와 `results/`(시나리오당 약 3MB 로그) 커밋 여부 결정
- [ ] (선택) T06 5xx 약 1.5K/s 출처 확인 (§9.3)
- [ ] (선택) AMI 구버전 컨테이너 문제와 교체 시 상호작용 (§8.4)
