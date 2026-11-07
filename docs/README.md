# 문서 안내

처음 보는 경우 [프로젝트 README](../README.md) → [아키텍처](../ARCHITECTURE.md) 순서로 읽는다. 특정 작업을 할 때는 아래에서 **필요한 문서만** 선택한다. 코드와 인프라의 실제 상태가 날짜별 기록보다 우선한다.

## 현재 작업과 실행 절차

| 궁금한 것 | 문서 |
| --- | --- |
| 전체 서비스 구조와 요청 흐름 | [아키텍처](../ARCHITECTURE.md) |
| 지금까지의 성과·다음 개선·하네스/API 자동화 범위 | [성능 개선 중간 정리](plans/performance-next-steps.md) |
| 앱 3대에서 API·워커 분리를 비교하는 조건과 채택 기준 | [API·워커 분리 A/B 계획](plans/api-worker-split-ab.md) |
| 하네스 구축과 성능 개선 순서 | [성능·관측 하네스 계획](plans/performance-harness.md) |
| Bridge Redis·Kafka 병목 계측과 로컬 시험 | [Bridge 관측 가이드](runbooks/bridge-observability.md) |
| Bridge batch claim 설계·비교 계획 (AWS 비교 완료, 결과는 [11-04 종합](reports/2026-11-04-ops-batch-claim-ack.md)) | [batch claim 설계](plans/bridge-batch-claim.md) |
| DLQ 재처리 Job 실행·재개·결과 대조 | [DLQ 배치 운영 가이드](runbooks/dlq-replay.md) |
| 부하 테스트 모드와 실행법 | [부하 테스트 가이드](../stress-test/TEST_GUIDE.md) |
| AWS 테스트 환경 기동·종료 | [운영 가이드](../ops/README.md) |
| MCP 지표 도구와 Slack 알림의 현재 관계 | [MCP 모니터링 코드 안내](../mcp-server/README.md) |
| AI 에이전트의 공통 작업 원칙 | [AGENTS.md](../AGENTS.md) |

`plans/`는 앞으로의 작업 계획이다. 완료한 구현이나 이미 검증된 성능이라고 읽지 않는다.

## 실험·장애 기록

`reports/`는 **해당 날짜의 상태와 근거를 보존하는 기록**이다. 본문에 당시의 브랜치, 미적용 상태, 과거 명령·파일 경로가 남아 있을 수 있다. 현재 적용 여부는 코드·Terraform·배포 상태를 다시 확인한다.

| 날짜 | 주제 | 문서 |
| --- | --- | --- |
| 2026-11-04 | **오늘 작업 종합**: 운영 N=1·10 비교(적용 전 A/B), N=10 ACK 병목과 묶음 ACK 수정·로컬 검증·운영 재시험, ElastiCache failover, scale-out, 다음 작업 | [11-04 작업 종합](reports/2026-11-04-ops-batch-claim-ack.md) |
| 2026-11-04 | (부록) 묶음 ACK 코드 변경 상세·시험 목록·지표 해석 | [Bridge ACK 묶음 처리](reports/2026-11-04-bridge-ack-batch.md) |
| 2026-11-03 | batch claim 로컬 정합성·장애 복구, Redis failover 정지 재현과 topology refresh 적용 후 회복, AWS 미실행 | [batch claim 로컬 검증](reports/2026-11-03-bridge-batch-claim-local.md) |
| 2026-11-03 | 재배포 없는 3대 재시험과 JVM 워밍업 관측 | [앱 3대 30만 건 재시험](reports/2026-11-03-app-warm-repeat-300k.md) |
| 2026-11-03 | 같은 30만 건의 앱 2대·3대 비교와 신규 노드 지연 | [앱 2대·3대 비교](reports/2026-11-03-app-2-vs-3-300k.md) |
| 2026-11-03 | 30만 건 유입·drain 비교와 claim 대기 분석 | [Bridge 30만 건 분석](reports/2026-11-03-bridge-drain-300k.md) |
| 2026-11-03 | 계측 배포 후 100·1만 건 AWS 검증과 Grafana 권한 복구 | [Bridge AWS 소량 검증](reports/2026-11-03-bridge-observability-aws-smoke.md) |
| 2026-11-03 | Bridge 호출 계측·로컬 Redis 검증, AWS 미실행 | [Bridge 관측 로컬 검증](reports/2026-11-03-bridge-observability-local.md) |
| 2026-11-01 | Consumer·DLQ 실패 시나리오, ACK 경계 수정과 로컬 장애 주입 검증 | [DLQ 실패 시나리오](reports/2026-11-01-dlq-failure-scenarios.md) |
| 2026-11-01 | DLQ 재처리 Job·중복 방지·복구 결과 대조 | [배치 로컬 검증](reports/2026-11-01-dlq-batch-validation.md) |
| 2026-10-17 | 500·750·1,000 RPS 10분 유지 평형점 탐색 | [지속 RPS 평형점 탐색](reports/2026-10-17-capacity-equilibrium.md) |
| 2026-10-15 | 지속 RPS 기준선 탐색과 다음 측정 계획 | [지속 RPS 탐색](reports/2026-10-15-capacity-baseline.md) |
| 2026-10-13 | ASG 증설 지연 단축과 실환경 검증 | [ASG 증설 검증](reports/2026-10-13-asg-scale-out-validation.md) |
| 2026-10-03 | AMI에 남은 앱 컨테이너 제거·재생성 조치 | [AMI 컨테이너 후속 조치](reports/2026-10-03-ami-container-remediation.md) |
| 2026-10-01 | RDS 장애 시 Consumer ack 보류만으로는 재전달되지 않음 재현 | [T06 Consumer 재처리 검증](reports/2026-10-01-t06-consumer-redelivery-verification.md) |
| 2026-09-28 | 150만 재현 중 active Set 제거 원인 추적 | [좀비 컨테이너 원인 분석](reports/2026-09-28-zombie-container-root-cause.md) |
| 2026-09-20 | AWS 환경 복구와 150만 건 재검증 | [150만 건 복구·재검증](reports/2026-09-20-aws-recovery-150m-validation.md) |

[202 접수 이후 경계 인계](reports/2026-11-01-accepted-202-boundaries.md)는 초기 조사와 남은 위험을 분리한 기록이다.

## 글과 보관 자료

- [`blog/`](blog/)는 블로그 설명용 글이다. 당시 구조를 설명한 글은 현행 운영 절차가 아니다.
- [`archive/`](archive/)는 현재 하네스 작업의 기본 참고 문서에서 제외한 과거 자료다. [2026-05 아키텍처](archive/2026-05-architecture.md), [프로젝트 심층 분석](archive/project-deep-dive-2026-05.md), [코드·면접 메모](archive/2026-05-code-interview-notes.md), [MCP 상세 설명](archive/2026-05-mcp-monitor-notes.md), [6월 변경 내역](archive/2026-06-08-v3-observability-legacy-cleanup.md), [6월 신뢰성·관측성 기록](archive/2026-06-10-reliability-observability.md)은 과거 맥락이 필요할 때만 읽는다.
- [`images/`](images/)는 보고서와 글에 쓰는 그림이다.

새 문서는 목적에 따라 `plans/`(갱신하는 계획), `reports/YYYY-MM-DD-topic.md`(그날의 실험·장애 근거), `blog/`(설명용 글) 중 한 곳에 둔다. 보고서의 날짜는 유지하되, 제목은 짧고 주제가 드러나게 짓는다. 파일을 이동하면 이 목차와 참조 링크도 함께 갱신한다.
