# API·워커 분리 로컬 종료·복구 검증

- 대상 앱 코드와 배포 설정은 `eb2c68c` 기준이다. Java 25로 JAR를 빌드해 로컬 컨테이너 두 개에서 같은 이미지로 실행했다.
- 로컬 Docker에서 API 1대, 워커 1대, Kafka 단일 브로커·파티션 3개, Redis Cluster primary 3개, MySQL 8.4를 사용했다. Bridge claim 묶음은 10이고 정기 배치는 양쪽에서 껐다.
- 재현 절차는 [로컬 장애 시험](../../stress-test/local-fault/bridge-batch-claim/README.md#api워커-분리-후-종료-시험)에 있다.

## 결과

| 시나리오 | HTTP 202 | Kafka 발행 | DB 성공 | 최종 Queue / in-flight / lag / DLQ |
| --- | ---: | ---: | ---: | --- |
| 분리 기동 후 정상 처리 | 20 | 20 | 20 | 0 / 0 / 0 / 0 |
| 워커 SIGTERM 후 재기동 | 20 | 20 | 20 | 0 / 0 / 0 / 0 |
| Kafka 중단 상태에서 워커 SIGKILL 후 재기동 | 120 | 120 | 120 | 0 / 0 / 0 / 0 |

정상 종료는 앞 캠페인의 드레인이 끝난 상태에서 진행했다. 워커 종료 코드 143과 Spring의 `Graceful shutdown complete` 로그를 확인했고 API는 계속 응답했다. 워커가 내려간 동안 새 캠페인에 접수된 20건은 Queue에 남았다. 재기동 후 모두 처리됐다.

강제 종료 시험은 Kafka를 멈춘 뒤 새 캠페인에 100건을 접수했다. SIGKILL 직전 Queue 90건과 in-flight 10건이 있었고 DB는 0건이었다. 워커 종료 코드 137을 확인했다. 종료 중 추가 접수한 20건은 Queue에 쌓였다. Kafka와 워커를 올린 뒤 Queue 110건이 먼저 DB에 반영됐다. Redis의 실제 5분 lease가 만료되자 워커가 10건을 회수했다. 최종 DB의 사용자와 순번은 각각 120개로 모두 유일했고 순번 1~120의 누락이 없었다. 이 재기동에서는 워커의 Actuator가 UP을 반환하고 약 35초 뒤에 Consumer가 파티션을 할당받았다.

관리 목록 조회는 API에서 200을 반환했다. 워커에서는 컨트롤러가 등록되지 않아 `NoResourceFoundException`이 발생했고 기존 전역 예외 처리기가 이를 500으로 응답했다. 워커 확인에는 Actuator와 Consumer 할당 상태를 사용한다.

Gradle 테스트는 114건 통과, 환경 의존 테스트 24건 건너뜀, 실패 0건이었다. 로컬에서는 실제 Redis·Kafka·MySQL을 사용했지만 AWS의 CodeDeploy/ASG 교체, 처리 중 정상 종료, Kafka 브로커 3대에서의 장애 복구, 부하에 따른 지연은 별도 검증 항목이다.

## 팀원 인계

1. [운영 가이드](../../ops/README.md#앱워커-레이아웃-전환)에 따라 AWS에서 공용 앱에 역할을 지원하는 번들을 먼저 배포한다. 이후 분리 구성에서 API·워커의 역할과 이미지 digest, ALB의 healthy API 2대, 워커의 Kafka 파티션 할당과 메트릭을 확인한다. 소량 캠페인으로 접수와 최종 DB 반영까지 확인한 뒤 A/B를 시작한다.
2. [A/B 계획](../plans/api-worker-split-ab.md)대로 공용 3대와 API 2대 + 워커 1대를 비교한다. 30만 건에서 p95/p99, 최종 반영 시간, 최대 Queue·in-flight, Redis 메모리를 남긴다.
3. 채택 후보에서 150만 건을 재검증하고 전체 접수·DB·드레인을 대조한다. 200만 건은 그 결과를 확인한 뒤 진행한다.
