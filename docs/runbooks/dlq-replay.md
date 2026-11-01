# DLQ Replay 배치 운영

## 업무 목적과 경계

접수 시 확정한 campaignId/userId/sequence를 유지한 채 실패 원본을 다시 Kafka로 보낸다. 재고를 다시 차감하거나 새 순번을 만들지 않는다. 일시 DB 장애는 Consumer nack이 처리하며, Replay는 운영자가 원인을 확인·해결한 격리 메시지를 대상으로 실행한다.

`dlqReplayJob`은 Start → Process → Finish의 Spring Batch Job이다. Process Tasklet은 한 번 호출할 때 한 건만 독립 트랜잭션(`REQUIRES_NEW`)으로 처리하고 커밋한 뒤 CONTINUABLE로 이어간다. 단순히 for-loop 안에서 100건씩 조회하는 방식과 달리 처리 결과·cursor·실행 건수가 매 항목 함께 커밋된다.

| 표시 | 의미 |
| --- | --- |
| RUNNING | 실행 시작됨. 중단된 프로세스의 기록일 수도 있으므로 실제 Job/앱 로그도 확인 |
| processedCount / lastMessageId | 커밋한 처리 건수와 재개 위치 |
| REPLAYED | Kafka 재발행 확인. DB 반영 완료가 아님 |
| SKIPPED | 이미 성공 이력이 있는 등 재발행 불필요 |
| FINAL_FAILED | 정책상 재처리 불가능한 메시지의 원본 보관. Kafka 연결 실패 횟수로 전환하지 않음 |
| publishFailedCount | 누적 발행 실패 시도 수. 재개 성공 후에도 감소하지 않음 |
| verification의 dbConfirmed | 원래 캠페인·사용자·순번에 해당하는 SUCCESS 행 확인 |

## 실행·가시화

관리자 API 접근이 가능한 테스트 환경에서 실행한다. URL은 매번 환경 출력으로 확인한다. `BASE_URL`은 운영자가 설정한다.

```sh
# 기본은 dry-run. 실제 발행은 명시적으로 dryRun:false를 준다.
curl --fail -H 'Content-Type: application/json' \
  -d '{"campaignId":1,"dryRun":true,"maxItems":10,"requestedBy":"operator"}' \
  "$BASE_URL/api/admin/dlq-replay"

# 반환받은 replayExecutionId를 사용한다.
bash ops/scripts/show-dlq-replay.sh "$BASE_URL" 123

# 원인을 해결한 뒤 새 실행을 생성한다.
curl --fail -H 'Content-Type: application/json' \
  -d '{"campaignId":1,"dryRun":false,"maxItems":10,"requestedBy":"operator"}' \
  "$BASE_URL/api/admin/dlq-replay"
```

출력의 실행 상태·시작/종료 시각·재발행/실패/건너뜀 수와 verification을 함께 캡처한다. 상세 결과는 최초 100건이며 이후 `/executions/{id}/verification?afterId=<마지막 itemId>&size=100`으로 넘긴다. verification summary는 해당 실행 전체 item 기준이다. 집계 숫자는 스냅샷이며 Consumer 처리 중에는 다음 조회에서 변할 수 있다. dry-run 결과는 DB 복구 성공으로 세지 않는다.

## 중단·실패 후 처리

```sh
# FAILED 실행: 장애 원인을 해결한 후 같은 실행 위치에서 재개
curl --fail -X POST "$BASE_URL/api/admin/dlq-replay/executions/123/resume"

# 프로세스 종료로 RUNNING이 남은 경우에만 사용한다.
# 이전 작업자가 실제로 종료됐는지 확인한 뒤 운영자가 명시적으로 확인한다.
curl --fail -X POST "$BASE_URL/api/admin/dlq-replay/executions/123/resume?confirmStopped=true"
```

완료한 실행과 종료 확인이 없는 RUNNING 실행의 재개는 409다. 없는 실행의 조회·verification·resume는 404다. `confirmStopped`는 운영자의 확인 표시이며 작업자 종료를 자동 감지하는 분산 잠금이 아니다. resume는 새 JobInstance를 만들지만 동일한 업무 executionId/cursor를 사용한다. 한 실행의 처리 위치는 DB 잠금으로 직렬화한다. 여러 실행이 같은 원본 행을 선택해도 잠금을 얻은 뒤 PENDING 여부를 다시 확인한다.

Kafka 발행이 실패하면 원본은 `PENDING`, 업무 실행과 Spring Batch Job은 `FAILED`로 남고 다음 메시지를 처리하지 않는다. 실패한 항목의 cursor와 processedCount를 전진시키지 않으므로 Kafka 복구 후 **같은 executionId를 resume**한다. 반복 연결 실패를 메시지 자체의 영구 실패로 판정하지 않는다.

실패 기록은 독립 트랜잭션으로 먼저 커밋하고 Tasklet에서 예외를 던져 Step을 실패시킨다. 같은 실행·메시지의 결과 행은 재개할 때 최신 결과로 갱신한다. 따라서 항목 행은 시도별 감사 로그가 아니며, 누적 실패 시도 수는 `publishFailedCount`와 원본 `replayAttemptCount`·로그로 확인한다.

Replay 전용 Producer는 `max.block.ms=1000`, `request.timeout.ms=1000`, `delivery.timeout.ms=5000`, `linger.ms=0`을 사용한다. send 진입 대기와 Future 대기(최대 5초)는 별개이므로 전체 실행을 정확히 1초 또는 5초로 보장하지 않는다. Bridge·일반 DLQ Producer 설정과 acks/idempotence는 유지한다. 대기 동안 원본 행 잠금을 보유하고 Tasklet 바깥 트랜잭션과 별도 커넥션이 필요할 수 있으므로 대량 병렬 Replay용 설계는 아니다.

발행 후 DB 커밋 전에 종료되면 cursor가 롤백되어 재발행될 수 있다. 정확히 한 번 발행을 보장하지 않는다. Consumer DB 고유 제약으로 중복 행을 막는다. Kafka 발행 실패와 결과 DB 저장 실패를 구분하며 후자는 트랜잭션 전체를 롤백한다.

## 원본 중복 저장 방지

Consumer는 원본 topic/partition/offset을 DLQ에 기록한다. 동일 원본의 재전달은 V9 유니크 제약으로 중복 보관을 막는다. 두 번째 저장이 충돌하면 이미 커밋된 원본이 존재하는지 확인한 경우에만 성공으로 반환한다. 위치가 없는 구버전 DLQ 행은 자동 역추정하지 않는다. 다른 offset의 동일 사용자 이벤트는 이 제약으로 합쳐지지 않으며 Consumer 업무 고유 제약이 최종 중복을 막는다.

기존 DLQ 위치 기록을 유지한 채 같은 이름의 토픽을 삭제·재생성하면 offset이 재사용될 수 있다. 해당 환경을 재생성할 때는 DLQ와 토픽의 원본 식별 범위를 함께 관리해야 한다.

## 로컬 하네스와 합격 기준

일반 단위 테스트는 `app/campaign-core`에서 `bash gradlew test --offline --no-daemon --max-workers=1`로 실행한다.

실제 DB 시험은 전용 MySQL 8.4, root 비밀번호 `batch-local-only`, 전용 DB `dlq_batch_test`에서만 실행한다. 호스트 포트는 127.0.0.1에 임시로 바인딩한다. 테스트가 자체 데이터를 삭제하므로 공유 DB를 지정하면 안 된다. 클래스는 로컬 전용 URL 패턴일 때만 활성화한다.

```sh
DLQ_BATCH_MYSQL_URL='jdbc:mysql://127.0.0.1:<포트>/dlq_batch_test?allowPublicKeyRetrieval=true&useSSL=false' \
  bash gradlew test --tests '*DlqReplayTransactionTest' --offline --no-daemon --max-workers=1
```

이 테스트는 실제 JPA 트랜잭션·Flyway V1~V10·DB 잠금으로 다음을 확인한다.

- 처리 위치 커밋과 다음 호출에서의 이어가기.
- 두 실행의 같은 원본 중복 발행 방지.
- Kafka 성공 뒤 결과 저장 실패 주입 → DB 롤백 → 재개·재발행.
- 동일 Kafka 원본의 DLQ 중복 저장 방지.
- 재발행 완료와 최종 DB 일치 여부 구분.

대부분 KafkaTemplate과 정책을 mock으로 두며, 한 시험은 실제 단일 KRaft 브로커·Producer·Consumer 클라이언트와 실제 Consumer 저장 코드를 연결한다. 연결 불가 주소에서 세 번 실행한 뒤 살아 있는 로컬 브로커 주소로 Producer를 교체해 5건의 원래 순번과 최종 DB 결과를 확인한다. 동일 주소 브로커 재기동이나 실제 앱의 `@KafkaListener` 컨테이너 복구 시험은 아니다.

**배치 메타데이터의 저장소를 구분한다.** 현재 앱 기본 구성은 메모리 JobRepository다. `BATCH_*` 테이블이 존재해도 앱 실행 이력이 자동 저장되지는 않으며 Job ID는 재시작 뒤 재사용될 수 있다. 운영 추적·재개 기준은 별도 영속 테이블 `dlq_replay_execution`/`dlq_replay_execution_item`의 업무 executionId다. 테스트에서 명시적으로 구성한 JDBC JobRepository의 세 Step 기록은 운영 앱의 JDBC 저장 증거가 아니다. 메모리 저장소에서도 실패→업무 cursor 기반 재개를 별도로 시험한다. 앱 JobRepository의 JDBC 전환은 후속 과제다.

배포 후에는 앱 로그·업무 실행 테이블·Queue/in-flight/lag·verification의 원래 순번을 대조한다. AWS·대규모 성능·실제 프로세스 강제 종료와 max.poll.interval 초과 시험은 별도다.

## 스키마 적용

V8은 실행 cursor/count를 추가하고 기존 item 기록에서 진행 위치를 보존한다. V9는 원본 위치 유니크 제약을 추가한다. V10은 Batch 6이 요구하는 BATCH_JOB_INSTANCE_SEQ를 만들고 기존 실행/legacy sequence보다 작은 ID를 발급하지 않도록 초기화한다. 적용 중에는 새 배치 실행을 멈춘다. 기존 V4는 변경하지 않는다.

PR의 이전 버전을 별도 시험 환경에 배포해 `PUBLISH_ATTEMPTS_EXHAUSTED`로 FINAL_FAILED가 남았다면 해당 원본을 먼저 대조한다. 이 변경은 기존 행을 자동 재분류하지 않는다. 정책상 영구 실패와 섞어 일괄 초기화하지 말고, 보존된 원본과 DB 결과를 확인한 대상만 운영 절차로 복구한다.
