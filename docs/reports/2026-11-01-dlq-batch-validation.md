# DLQ Replay 배치 구현·로컬 검증

> 상태: 로컬 구현·검증 완료, 배포·실환경 검증 미실행.
> 검증 소스: `6b40999f015b48b6c534518107eba2502eb8c88a` (이후 문서만 추가).
> 기반: #112가 반영된 origin/main에서 `feat/dlq-replay-batch-recovery` 생성.

## 왜 배치를 쓰는가

선착순 순번은 Redis 접수 때 확정된다. 배치는 그 순번을 새로 부여하는 작업이 아니라, 원인 확인을 마친 DLQ 원본을 기존 campaignId/userId/sequence 그대로 재발행하는 운영 작업이다. 실행자·필터·최대 건수·dry-run·항목별 결과와 재개 위치를 관리하기 위해 Spring Batch Job/Step과 실행 메타데이터를 사용한다.

Consumer의 일시 DB 장애 재시도, Bridge의 claim 만료 복구는 별도 실시간 경로다. Replay는 관리자 수동 실행이며, 기본 dry-run 뒤 명시적 실제 실행으로 진행한다. 재고 반환·당첨 취소 정책을 자동 추가하지 않았다.

## 바꾼 동작

- DLQ에 원본 topic/partition/offset을 저장하고 동일 원본 재전달은 DB 유니크 제약으로 중복 저장을 방지한다. 기존 위치 없는 행은 그대로 남긴다.
- Start → Process → Finish의 기존 Job 유지. Process Tasklet 한 호출이 한 건을 처리·커밋한 뒤 반복한다. cursor와 결과가 같은 트랜잭션으로 저장된다.
- 실행 행과 원본 행에 DB 잠금을 사용한다. 경쟁 실행은 잠금 후 원본 상태를 다시 확인한다. Kafka Future 대기 최대 5초에 더해 send의 max.block.ms도 잠금 유지 시간에 포함된다.
- Kafka 발행 성공 뒤 DB 결과 저장 실패는 롤백한다. 재개 시 중복 발행은 가능하며 Consumer DB 고유 제약에 의존한다.
- 같은 executionId를 resume하면 저장한 위치에서 이어간다. Kafka 발행 실패 항목은 다음 운영자 실행에서 재시도하고 3회 발행 실패는 FINAL_FAILED로 원본을 보관한다.
- `/executions/{id}/verification`과 확인 스크립트로 재발행 건수와 원래 순번까지 일치하는 DB 결과를 분리해 보여준다.
- 실제 Job 시험에서 V4의 legacy BATCH_JOB_SEQ와 Batch 6의 BATCH_JOB_INSTANCE_SEQ 불일치를 발견했다. V10에서 기존 실행 ID를 보존하며 필요한 sequence 테이블을 초기화했다.

## 검증

MySQL 8.4.13, 별도 로컬 DB `dlq_batch_test`, 실제 JPA·Flyway V1~V10·트랜잭션·행 잠금. KafkaTemplate·정책 일부·Slack은 mock이다. 정책의 기존 별도 단위 테스트도 전체 테스트에 포함했다.

```sh
# 전용 로컬 DB에서만 실행 (시험 데이터 삭제)
DLQ_BATCH_MYSQL_URL='jdbc:mysql://127.0.0.1:<임시포트>/dlq_batch_test?allowPublicKeyRetrieval=true&useSSL=false' \
  bash gradlew test --offline --no-daemon --max-workers=1
```

총 70건, 실패 0, 오류 0, 건너뜀 1. 실제 실행 69건 통과. 이 중 `DlqReplayTransactionTest`의 DB 통합 시험 7건을 실행했다.

| 시험 | 결과 |
| --- | --- |
| 원래 재발행 값·cursor 이동, dry-run, 최대 건수 | 단위 테스트 통과 |
| 결과 저장 실패를 발행 실패로 오인하지 않음 | 단위 테스트 통과 |
| 커밋된 위치 이후 다음 호출 | DB 시험 통과 |
| 두 실행의 같은 원본 처리 | Kafka mock 발행 1회 확인 |
| Kafka 성공 후 결과 INSERT 실패 | DB 트리거로 주입. cursor/메시지 상태 롤백 후 다음 호출에서 재발행·커밋 확인 |
| 동일 topic/partition/offset 두 번 DLQ 저장 | DB 행 1개 확인 |
| 재발행 성공과 DB 복구 구분 | 발행 1·dbConfirmed 0 → 원래 순번의 성공 행 생성 후 dbConfirmed 1 |
| 실제 dlqReplayJob 구동 | 세 Step COMPLETED, Process COMMIT_COUNT 2·READ_COUNT 1 확인 |

실제 메타데이터에서 관측한 한 실행:

| Step | 상태 | 커밋 횟수 | 읽은 항목 |
| --- | --- | --- | --- |
| dlqReplayStartStep | COMPLETED | 1 | 0 |
| dlqReplayProcessStep | COMPLETED | 2 | 1 |
| dlqReplayFinishStep | COMPLETED | 1 | 0 |

운영 실행 명령과 출력 해석은 [배치 운영 가이드](../runbooks/dlq-replay.md)를 따른다. API 상세 목록은 페이지 크기를 제한하고 summary는 전체 실행 기준이다.

## 남은 경계와 다음 단계

- 실제 Kafka 재소비와 최종 DB 반영을 연결한 배포 후 소량 smoke는 아직 하지 않았다. 이 기록으로 실환경 완료나 150만 성능 개선을 주장하지 않는다.
- 실제 프로세스 SIGKILL 대신 DB 결과 저장 실패·재호출로 트랜잭션 복구 경계를 시험했다. 완전한 강제 종료 시험은 별도다.
- Kafka 발행과 MySQL 커밋은 원자적이지 않다. 같은 이벤트 재발행 가능성을 유지한다.
- 일괄 DLQ 정리·토픽 재생성 시 source offset 재사용에 주의한다. 기존 위치 없는 DLQ 중복은 자동 통합하지 않는다.
- Job의 COMPLETED와 REPLAYED는 최종 사용자 복구 완료를 뜻하지 않는다. verification 결과를 대조한다.
- 메트릭은 시도 관측용이며, 롤백을 포함한 물리적 DB 저장 건수는 DB 기록을 기준으로 판단한다.
- 운영자는 배치 실행을 멈춘 상태에서 V8~V10을 적용한다. 실환경 배포는 별도이며 AWS 자원은 이번 작업에서 변경하지 않았다.
- 후속: 배포 버전 확인·실제 Job smoke → 150만 기준선 → 자원 경쟁·드레인 병목 개선 → 200만 총량 검증.
