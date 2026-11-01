# DLQ Replay 배치 구현·로컬 검증

> 상태: PR #113 리뷰 후 수정·로컬 재검증 완료. AWS 배포·150만 기준선은 미실행.
> 초기 검증 소스: `6b40999f015b48b6c534518107eba2502eb8c88a` / 리뷰 기준 head: `1c39c6a80c15373db98db1e3eddd250d7853e752`.
> 후속 수정: 2026-11-02, PR #113의 리뷰 반영 커밋. 초기 70건 결과를 아래 79건 결과로 갱신했다.

## 왜 배치를 쓰는가

선착순 순번은 Redis 접수 때 확정된다. 배치는 그 순번을 새로 부여하는 작업이 아니라, 원인 확인을 마친 DLQ 원본을 기존 campaignId/userId/sequence 그대로 재발행하는 운영 작업이다. 실행자·필터·최대 건수·dry-run·항목별 결과와 재개 위치를 관리하기 위해 Spring Batch Job/Step과 별도의 업무 실행 테이블을 사용한다.

Consumer의 일시 DB 장애 재시도, Bridge의 claim 만료 복구는 별도 실시간 경로다. Replay는 관리자 수동 실행이며 기본 dry-run 뒤 명시적 실제 실행으로 진행한다. 재고 반환·당첨 취소 정책을 자동 추가하지 않았다.

## 검증 보고서와 리뷰에서 확인한 문제

사용자가 전달한 `2026-11-01-pr113-dlq-batch-verification.md`는 이전 head의 실제 앱 검증 결과다. 아래는 그 보고서의 관측을 근거로 한 수정이며, 이 문서 작성자가 모든 외부 시험을 다시 수행했다는 뜻은 아니다.

| 확인한 문제 | 수정·판단 |
| --- | --- |
| Kafka 장애 중 약 61초씩 대기, 3회 실패 뒤 정상 원본 FINAL_FAILED·복구 4/5 | 연결 실패는 PENDING 유지. 첫 발행 실패에서 Job을 FAILED로 멈추고 같은 cursor로 재개. 전용 Producer의 send 대기 제한 |
| 없는 실행 ID의 resume·verification이 조회와 다른 HTTP 상태 | CodeRabbit 지적 반영. 실행 조회에서 발생한 예외만 404로 매핑 |
| RUNNING 작업을 확인 없이 다시 실행 가능 | 기본 409. 작업자 종료를 확인한 운영자만 confirmStopped=true로 재개 |
| 실제 앱의 BATCH_* 행이 비어 있음 | 현재 앱은 메모리 JobRepository. JDBC 저장소를 명시한 테스트와 런타임의 차이를 문서에 정정. JDBC 전환은 후속 |

제공 보고서는 기존 데이터 마이그레이션, 실제 앱의 SIGKILL 후 업무 cursor 재개, DLQ 재전달 중복 방지와 DB 장애 복구도 기록했다. 이 결과는 이전 코드의 증거로 구분한다. 보고서에서 미완료인 장시간 DB 장애(H7)·브로커 중단(H8)을 통과로 바꾸지 않는다.

## 현재 동작

- 원본 topic/partition/offset을 DLQ에 저장하고 DB 유니크 제약으로 같은 원본 재전달의 중복 저장을 막는다. 위치 없는 기존 행은 추정해서 합치지 않는다.
- Start → Process → Finish Job 유지. 한 항목의 결과·원본 상태·cursor는 독립 트랜잭션으로 함께 커밋한다. 경쟁 실행은 잠금 후 원본 상태를 다시 확인한다.
- Kafka 발행 실패는 실패 기록만 커밋하고 원본 PENDING·cursor·processedCount를 보존한다. Tasklet 예외로 Process Step과 Job을 FAILED로 종료한다. 같은 executionId를 resume하면 해당 항목부터 재시도한다.
- 반복 발행 실패를 FINAL_FAILED로 바꾸지 않는다. 동일 실행/메시지 결과 행은 최신 결과로 갱신하고 실패 시도 수는 누적 카운터로 남긴다.
- Replay 전용 Producer의 max.block/request.timeout은 1초, delivery.timeout은 5초, linger는 0이다. Future 대기도 최대 5초이며 전체 실행 시간의 하드 리밋은 아니다. Bridge·기존 DLQ Producer는 변경하지 않았다.
- Kafka 발행 성공 뒤 DB 결과 저장 실패는 롤백한다. 재개 시 중복 발행은 가능하고 Consumer DB 고유 제약에 의존한다.
- verification과 확인 스크립트는 재발행과 실제 DB의 campaign/user/sequence 일치를 분리해서 보여준다.

## 직접 재실행한 로컬 검증

MySQL 8.4.13, 전용 `dlq_batch_test`, JPA·Flyway V1~V10·트랜잭션·행 잠금을 사용했다. 정책 일부·Slack은 mock이다. AWS 자원은 사용하지 않았다.

```sh
# 저장소 루트에서 전용 로컬 DB에만 실행 (시험 데이터 삭제)
DLQ_BATCH_MYSQL_URL='jdbc:mysql://127.0.0.1:<임시포트>/dlq_batch_test?allowPublicKeyRetrieval=true&useSSL=false' \
  bash app/campaign-core/gradlew -p app/campaign-core test --offline --no-daemon --max-workers=1
```

**79건 중 실패 0·오류 0·건너뜀 1, 실제 78건 통과.** DB 연동 클래스 10건은 모두 실행했다. 일반 단위 테스트, 정책 테스트, Consumer·DLQ·Bridge 기존 테스트도 전체 실행에 포함된다.

| 시험 | 결과 |
| --- | --- |
| cursor 커밋·다음 호출 재개, 경쟁 실행, dry-run·최대 건수 | 통과 |
| Kafka 성공 후 결과 INSERT 실패 | DB 트리거 주입. cursor/원본 롤백 후 재발행·커밋 |
| 동일 topic/partition/offset의 DLQ 저장 | 원본 행 1개 |
| 재발행 성공과 DB 복구 구분 | published 1/dbConfirmed 0 → 원래 순번 DB 저장 후 dbConfirmed 1 |
| Kafka mock 연속 3회 실패 후 같은 실행 재개 | 각 Job FAILED, 원본 PENDING·cursor 0. 복구 후 COMPLETED·결과 행 1개 |
| 실제 Producer로 연결 불가 주소에 3회 실행 | 1,286ms / 1,183ms / 1,196ms, 각각 FAILED·원본 5건 PENDING·cursor 0 |
| 살아 있는 단일 KRaft 브로커로 전환 후 같은 실행 재개 | Kafka 5건 → 실제 Consumer 저장 코드 호출 → DB 5건, 원래 순번 일치·dbConfirmed 5 |
| 메모리 JobRepository로 Job 실행 | 실패 뒤 같은 업무 executionId/cursor로 재개 완료 |
| 없는 실행의 resume·verification / RUNNING·COMPLETED 재개 | 404 / 409, 단위 시험 |
| Replay Producer 설정 격리 | 원래 Producer max.block 60초 유지, acks=all/idempotence 유지 |

실제 Kafka 시험은 정책의 payload 구성을 mock으로 대체했고 KafkaConsumer가 읽은 레코드를 실제 Consumer 메서드에 전달했다. Kafka 연결 불가 후에는 테스트용 Producer를 살아 있는 브로커 주소로 교체했다. 동일 주소에서의 브로커 재시작·Producer 자동 재접속, 앱 전체 HTTP→@KafkaListener E2E나 3브로커 리더 재선출을 검증한 시험이 아니다.

## Spring Batch 이력과 업무 실행 이력의 차이

초기 테스트는 **명시적으로 JDBC JobRepository를 구성**했다. 그 설정에서 Start/Process/Finish 세 Step COMPLETED와 Process의 반복 커밋을 확인했다. V10의 BATCH_JOB_INSTANCE_SEQ 보완도 이 JDBC 설정에서 필요했다.

현재 앱 기본 구성은 메모리 JobRepository이며, BATCH_* 테이블 생성만으로 JDBC 저장이 활성화되지 않는다. 앱 재시작 후 Batch jobExecutionId는 영속 추적 키로 사용할 수 없다. 운영 추적·재개에는 영속 `dlq_replay_execution.id`, lastMessageId, processedCount와 항목 결과를 사용한다. **앱 JDBC JobRepository 전환은 이번 수정에 포함하지 않았다.**

## 남은 경계와 다음 단계

- 앱 배포 후 소량 smoke와 150만 기준선, 장시간 DB 장애·실제 브로커 중단·리더 재선출 시험은 별도다. 로컬 성공으로 실환경 성능 향상을 주장하지 않는다.
- Kafka와 MySQL 커밋은 원자적이지 않다. 늦은 발행 성공이나 DB 롤백 뒤 재발행으로 중복이 생길 수 있다.
- confirmStopped는 운영자 확인이며 자동 작업자 감지/분산 lease가 아니다.
- 독립 트랜잭션은 Tasklet 외부 트랜잭션과 별도 DB 커넥션이 필요할 수 있다. 소량 수동 운영 범위에서 사용하며 대량 병렬 실행용 설계가 아니다.
- 이전 PR 버전에서 PUBLISH_ATTEMPTS_EXHAUSTED로 분류한 기존 행은 자동 변경하지 않는다. 원본·DB 결과 확인 후 대상별 복구가 필요하다.
- 토픽 삭제·재생성에 따른 source offset 재사용, 위치 없는 구버전 DLQ 중복은 별도다.
- V10의 VALUES(ID)는 현재 MySQL에서 동작하지만 deprecated 경고가 남는다. 이미 적용한 migration의 체크섬을 바꾸지 않았으며 SQL 정리는 후속 스키마 작업에서 다룬다.
- Job COMPLETED/REPLAYED는 사용자 DB 복구 완료가 아니다. verification으로 대조한다. 항목은 최신 결과이고 카운터·메트릭은 시도 관측값이다.
- 배치 실행을 멈춘 상태에서 V8~V10 적용 → 배포 소량 smoke → 150만 기준선 → API/워커 자원 경쟁·드레인 병목 개선 → 200만 총량 검증 순서로 진행한다.

운영 명령과 판단 기준은 [배치 운영 가이드](../runbooks/dlq-replay.md)를 따른다. 전체 프로젝트 README는 이번 수정에서 변경하지 않았다.
