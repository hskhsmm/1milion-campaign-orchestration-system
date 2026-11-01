# DLQ Replay 배치 운영

## 업무 목적과 경계

접수 시 확정한 campaignId/userId/sequence를 유지한 채 실패 원본을 다시 Kafka로 보낸다. 재고를 다시 차감하거나 새 순번을 만들지 않는다. 일시 DB 장애는 Consumer nack이 처리하며, Replay는 운영자가 원인을 확인·해결한 격리 메시지를 대상으로 실행한다.

`dlqReplayJob`은 Start → Process → Finish의 Spring Batch Job이다. Process Tasklet은 한 번 호출할 때 한 건만 처리하고 커밋한 뒤 CONTINUABLE로 이어간다. 단순히 for-loop 안에서 100건씩 조회하는 방식과 달리 처리 결과·cursor·실행 건수가 매 항목 함께 커밋된다.

| 표시 | 의미 |
| --- | --- |
| RUNNING | 실행 시작됨. 중단된 프로세스의 기록일 수도 있으므로 실제 Job/앱 로그도 확인 |
| processedCount / lastMessageId | 커밋한 처리 건수와 재개 위치 |
| REPLAYED | Kafka 재발행 확인. DB 반영 완료가 아님 |
| SKIPPED | 이미 성공 이력이 있는 등 재발행 불필요 |
| FINAL_FAILED | 자동 재발행을 멈추고 원본을 보관. 당첨 취소·재고 반환을 의미하지 않음 |
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
# 프로세스가 중단된 것을 확인한 후 같은 실행 위치에서 재개
curl --fail -X POST "$BASE_URL/api/admin/dlq-replay/executions/123/resume"
```

완료한 실행은 재개할 수 없다. resume는 새 JobInstance를 만들지만 동일한 업무 executionId/cursor를 사용한다. 한 실행의 처리 위치는 DB 잠금으로 직렬화한다. 여러 실행이 같은 원본 행을 선택해도 잠금을 얻은 뒤 PENDING 여부를 다시 확인한다.

Kafka 응답 실패 항목은 이 실행에서 다시 돌지 않고 다음 운영자 실행의 대상이 된다. 발행 시도 3회 실패하면 FINAL_FAILED로 보관한다. Kafka 장애를 해결하기 전에 반복 실행해 시도를 소모하지 않는다. Future 대기는 5초이며 send() 진입 자체는 Producer의 max.block.ms 영향도 받는다. 이 시간 동안 해당 원본 행의 DB 잠금을 유지하는 절충안이다. 대량 병렬 Replay용 설계는 아니다.

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

KafkaTemplate은 mock이다. 실제 Job을 실행해 세 Step COMPLETED와 Process의 반복 커밋도 확인한다. Kafka 재소비·AWS·대규모 성능은 이 테스트만으로 통과 처리하지 않는다. 배포 후 격리된 합성 캠페인에서 실제 Job 3개 Step의 BATCH_STEP_EXECUTION 기록, Queue/in-flight/lag, verification의 원래 순번 일치까지 대조해야 한다. 실제 강제 종료와 max.poll.interval 초과 시험도 별도다.

## 스키마 적용

V8은 실행 cursor/count를 추가하고 기존 item 기록에서 진행 위치를 보존한다. V9는 원본 위치 유니크 제약을 추가한다. V10은 Batch 6이 요구하는 BATCH_JOB_INSTANCE_SEQ를 만들고 기존 실행/legacy sequence보다 작은 ID를 발급하지 않도록 초기화한다. 적용 중에는 새 배치 실행을 멈춘다. 기존 V4는 변경하지 않는다.
