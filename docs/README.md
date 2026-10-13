# 문서 안내

처음 보는 경우 [프로젝트 README](../README.md) → [아키텍처](../ARCHITECTURE.md) 순서로 읽는다. 특정 작업을 할 때는 아래에서 **필요한 문서만** 선택한다. 코드와 인프라의 실제 상태가 날짜별 기록보다 우선한다.

## 현재 작업과 실행 절차

| 궁금한 것 | 문서 |
| --- | --- |
| 전체 서비스 구조와 요청 흐름 | [아키텍처](../ARCHITECTURE.md) |
| 하네스 구축과 성능 개선 순서 | [성능·관측 하네스 계획](plans/performance-harness.md) |
| 부하 테스트 모드와 실행법 | [부하 테스트 가이드](../stress-test/TEST_GUIDE.md) |
| AWS 테스트 환경 기동·종료 | [운영 가이드](../ops/README.md) |
| MCP 지표 도구와 Slack 알림의 현재 관계 | [MCP 모니터링 코드 안내](../mcp-server/README.md) |
| AI 에이전트의 공통 작업 원칙 | [AGENTS.md](../AGENTS.md) |

`plans/`는 앞으로의 작업 계획이다. 완료한 구현이나 이미 검증된 성능이라고 읽지 않는다.

## 실험·장애 기록

`reports/`는 **해당 날짜의 상태와 근거를 보존하는 기록**이다. 본문에 당시의 브랜치, 미적용 상태, 과거 명령·파일 경로가 남아 있을 수 있다. 현재 적용 여부는 코드·Terraform·배포 상태를 다시 확인한다.

| 날짜 | 주제 | 문서 |
| --- | --- | --- |
| 2026-10-13 | ASG 증설 지연 단축과 실환경 검증 | [ASG 증설 검증](reports/2026-10-13-asg-scale-out-validation.md) |
| 2026-10-03 | AMI에 남은 앱 컨테이너 제거·재생성 조치 | [AMI 컨테이너 후속 조치](reports/2026-10-03-ami-container-remediation.md) |
| 2026-09-28 | 150만 재현 중 active Set 제거 원인 추적 | [좀비 컨테이너 원인 분석](reports/2026-09-28-zombie-container-root-cause.md) |
| 2026-09-20 | AWS 환경 복구와 150만 건 재검증 | [150만 건 복구·재검증](reports/2026-09-20-aws-recovery-150m-validation.md) |

## 글과 보관 자료

- [`blog/`](blog/)는 블로그 설명용 글이다. 당시 구조를 설명한 글은 현행 운영 절차가 아니다.
- [`archive/`](archive/)는 현재 하네스 작업의 기본 참고 문서에서 제외한 과거 자료다. [2026-05 아키텍처](archive/2026-05-architecture.md), [프로젝트 심층 분석](archive/project-deep-dive-2026-05.md), [코드·면접 메모](archive/2026-05-code-interview-notes.md), [MCP 상세 설명](archive/2026-05-mcp-monitor-notes.md), [6월 변경 내역](archive/2026-06-08-v3-observability-legacy-cleanup.md), [6월 신뢰성·관측성 기록](archive/2026-06-10-reliability-observability.md)은 과거 맥락이 필요할 때만 읽는다.
- [`images/`](images/)는 보고서와 글에 쓰는 그림이다.

새 문서는 목적에 따라 `plans/`(갱신하는 계획), `reports/YYYY-MM-DD-topic.md`(그날의 실험·장애 근거), `blog/`(설명용 글) 중 한 곳에 둔다. 보고서의 날짜는 유지하되, 제목은 짧고 주제가 드러나게 짓는다. 파일을 이동하면 이 목차와 참조 링크도 함께 갱신한다.
