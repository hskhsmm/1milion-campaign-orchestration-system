# 내맘대로캠페인 성능·관측 하네스 도입 로드맵

> 작성: 2026-10-12
> 상태: 제안·실행 계획. 이 문서는 구현 완료나 성능 개선 효과를 주장하지 않는다.
> 범위: [이슈 #95](https://github.com/hskhsmm/1milion-campaign-orchestration-system/issues/95), CPU 관측·증설 지연, p95/p99, 지속 가능한 End-to-End 처리량, 기존 MCP 모니터링의 역할 정리, 후속 Kafka 인프라 코드화 판단.

## 1. 왜 지금 하네스인가

우리는 이미 환경을 켜고 끄는 스크립트, k6 테스트, Prometheus/Grafana, 정합성 확인 절차를 갖고 있다. 부족한 것은 **같은 조건에서 실험하고, 결과를 자동 수집하며, 이전 결과와 비교해 합격 여부를 설명하는 연결 고리**다.

2026-09-20의 ASG 3대(`t3.small`)·150만 건 테스트에서는 최종 DB 150만 건, 정합성 차이 0, 5xx 0을 확인했다. 그러나 유입 중 API 접수는 약 5,000~5,336건/s였던 반면 Bridge와 Consumer는 약 1,700건/s였고, API 유입 종료 후 후단 처리량은 약 4,100건/s로 상승했다. 앱 process CPU 최고값은 98.24%, API p95의 테스트 중 관측 최고값은 3.38초였다. 이는 **동시 유입 시 자원 경쟁이 있다는 강한 근거**이지만, 현재의 지속 가능한 E2E TPS나 p99 기준값을 확정해 주지는 않는다. 서로 다른 시점의 peak를 합쳐 하나의 처리량으로 말해서도 안 된다.

따라서 다음 작업은 서버를 무작정 증설하거나 설정값을 먼저 바꾸는 일이 아니다. **실험 계약 → 기준선 → 병목 분해 → 한 변수씩 개선 → 정합성 재검증**을 반복할 수 있게 만드는 일이다.

## 2. 무엇을 하네스라고 부를 것인가

이 프로젝트의 하네스는 AI가 AWS를 마음대로 조작하는 시스템이 아니다. 다음 네 층을 결합한 **재현 가능한 개발·검증 절차**다.

| 층 | 역할 | 우선 재사용할 자산 |
| --- | --- | --- |
| 작업 계약 | 에이전트·개발자가 같은 원칙과 안전 경계를 따름 | 저장소 루트의 짧은 `AGENTS.md`, `docs/README.md`, `ARCHITECTURE.md`, 이 문서 |
| 실행 | 환경 점검, smoke, 부하, drain, 정합성 순서를 고정 | `ops/Makefile`, `stress-test/run-test.sh`, `stress-test/TEST_GUIDE.md` |
| 증거 | k6·Prometheus·CloudWatch·AWS 상태를 같은 시간축으로 수집 | Grafana 대시보드, Prometheus, AWS CLI/SSM |
| 판정 | 수치와 실패 이유를 남기고 이전 실행과 비교 | 실행별 manifest·요약 리포트·raw artifact(추가 예정) |

`AGENTS.md`는 모델 학습이나 장기 기억이 아니다. 저장소에서 작업할 때 적용할 **짧은 지침**이다. 긴 장애 연대기나 모든 명령어를 복사하지 않고, 관련 작업에서 어느 문서를 읽어야 하는지 안내한다. 공식 OpenAI 문서도 프로젝트 루트의 `AGENTS.md`로 저장소 지침을 제공하며, 항상 적용되는 지침은 간결하게 유지하라고 권한다.

## 3. 성공을 어떻게 정의할 것인가

### 3.1 서로 다른 두 지연을 분리한다

```text
사용자 관점: 요청 → 202 Accepted             = API 응답 지연(p50/p95/p99)
업무 완료 관점: 접수 → Redis → Kafka → DB 반영 = E2E 반영 지연(p50/p95/p99)
```

API의 p95/p99를 낮추면서 Redis Queue가 계속 증가하거나 DB 반영이 늦어지면 성공이 아니다. 반대로 후단만 빨라져도 API의 긴 꼬리 지연을 해결했다고 말할 수 없다. `consumer.pending_to_success.latency`는 현재 Consumer batch 시작부터 DB 처리 완료까지의 구간만 다루므로 전체 E2E 지연의 대체값으로 쓰지 않는다.

### 3.2 실험 합격 조건

최종 수치 목표는 기준선을 측정한 뒤 정한다. 그 전에도 아래 조건은 고정한다.

1. `dropped_iterations=0`, 예상치 못한 5xx 0건. 202와 거절 응답(400/429 등)의 지연은 분리해서 보고한다.
2. 같은 목표 RPS의 안정 구간에서 API 202 p95·p99를 비교한다. 단일 테스트 전체 평균이나 순간 최댓값만으로 결론 내리지 않는다.
3. Redis Queue와 Kafka lag가 안정 구간에서 계속 발산하지 않는다. accepted rate와 DB 반영 rate를 함께 기록한다.
4. 접수→DB 반영 p95·p99와 oldest-event age를 기록한다. 계측을 도입하기 전에는 이 항목을 **미측정**으로 표기한다.
5. 테스트 종료 후 Queue·lag가 0으로 수렴하고, 최종 DB row 수·재고·접수 건수의 정합성이 맞는다.
6. 자원과 비용 비교 시 인스턴스 수·종류, Kafka 파티션, RDS/Redis 사양, 로그 수준, 테스트 위치와 실행 시간을 기록한다.

`consumer_db_committed_total`은 현재 DB 성공 경로를 통과한 이벤트 수다. `INSERT IGNORE`가 중복을 무시한 경우에도 물리적 신규 INSERT 수와 다를 수 있으므로, 최종 row count와 혼동하지 않는다.

## 4. 첫 번째 구현: 작은 하네스

### 4.1 저장소 지침

루트 `AGENTS.md`에는 다음만 둔다.

- 서비스 경계와 환경 기동 절차의 권위 있는 문서 링크.
- `integrity` 테스트와 `capacity` 테스트의 목적 차이.
- 202 TPS, Kafka publish TPS, DB 성공 경로 TPS, 최종 INSERT 건수를 섞어 말하지 않는 규칙.
- 로컬 단위 테스트는 변경 범위에 맞게 실행하되, 비용이 발생하는 `env-up`, 실환경 부하 테스트, Terraform apply, 배포, 머지 등은 해당 작업의 명시적 요청과 사전 점검을 요구한다는 경계.
- 성능 변경의 완료 조건: 변경 전·후 같은 조건의 수치, 실패/정합성, 비용 또는 사용 자원, 남은 불확실성을 보고.

지침은 처음부터 완벽할 필요가 없다. 두세 번 실제 작업에 적용해 불필요한 규칙을 줄이고 빠진 규칙을 보강한다. 반복 절차가 안정된 뒤에만 별도 Skill이나 자동화로 승격한다.

### 4.2 실행별 결과물

기존 스크립트를 교체하기보다 감싸는 작은 래퍼를 권한다. 예컨대 `stress-test/harness/`에 다음 기능을 단계적으로 둔다.

| 결과물 | 필수 내용 |
| --- | --- |
| 실행 manifest | 실행 ID·시간대·Git SHA·배포 이미지/AMI·실행 모드·RPS 단계·재고·ASG desired/in-service·인스턴스 타입·Kafka 파티션 |
| 사전 점검 | AWS 계정/리전, ALB target healthy, 배포 버전, Redis/RDS/Kafka 상태, 부하 발생기 상태 |
| 원본 증거 | k6 JSON summary, 시간 구간별 Prometheus/CloudWatch 원본, 주요 AWS 상태, 정합성 결과 |
| 판정 요약 | API 202 p95/p99, 5xx/429, dropped iterations, Queue 기울기, lag, DB 반영율, E2E 지연, CPU, 최종 diff, PASS/FAIL/INCONCLUSIVE |

**INCONCLUSIVE**는 중요한 결과다. 지표가 누락되거나, 테스트 도중 ASG/배포/설정이 바뀌거나, 부하 발생기가 목표 RPS를 내지 못했다면 성공·실패를 억지로 판정하지 않는다. 보고서에는 원본 쿼리, 집계 창, 단위, 인스턴스별/전역 집계 방식을 기록해 재계산 가능하게 한다. 비밀값, 개인 식별자, 대량 원본 이벤트는 artifact에 저장하지 않는다.

### 4.3 자동화 경계

초기에는 **읽기·로컬 검증·리포트 생성만 자동화**한다. 실환경 기동/종료 및 고부하 실행은 사람이 범위를 확인한 뒤 명시적으로 시작한다. 실패 시 하네스는 부하를 중지하고 현재 상태·복구 절차를 출력하되, 데이터 삭제·Terraform 변경·머지를 자동 수행하지 않는다. `env-down`은 최종 증거와 정합성 확인 뒤 별도 단계로 둔다.

## 5. 기준선 실험: #95를 수치로 바꾸기

기존 `capacity` 모드를 사용해 예를 들어 `1000,1500,2000,2500,3000` RPS를 단계별 300초 유지한다. 이 수치는 **탐색 시작점**이며 SLO나 이미 검증된 한계가 아니다. 가장 높은 RPS부터 밀어붙이지 말고, 낮은 단계가 안정적인지 확인하며 상승한다. 사전 warm-up과 종료 후 drain 관찰 시간을 포함한다.

반드시 다음 조건을 같게 유지한다.

- 동일한 코드/설정/로그 수준, 동일한 앱 인스턴스 수와 사양, 동일한 Redis·RDS·Kafka 구성.
- 같은 부하 발생기 위치와 충분한 k6 VU. `dropped_iterations`가 생기면 시스템 성능이 아니라 부하 생성 실패일 수 있다.
- 재고가 테스트 중 고갈되지 않게 하고, 사용자 ID·캠페인 조건을 일정하게 유지한다.
- ASG scale-out이 일어나면 해당 시각을 기록한다. 고정 용량 비교와 자동 확장 시험은 별도 실행으로 분리한다.
- 같은 RPS에서 적어도 두 차례 반복해 변동폭을 확인한다. 비용 때문에 반복이 어렵다면 결과의 신뢰도 제한을 명시한다.

150만 건 `integrity` 모드는 **최종 정합성과 burst 복구**를 검증한다. 일정 유입률의 `capacity` 모드는 **지속 가능한 처리량과 p95/p99**를 검증한다. 어느 하나가 다른 하나를 대신하지 않는다.

## 6. CPU 관측과 느린 증설을 분해한다

“CPU 100%인데 왜 EC2가 늦게 생기나?”는 다음 시간을 분리해야 답할 수 있다.

```text
부하 시작
  → 앱/EC2 CPU 상승
  → CloudWatch/ASG가 판단에 사용할 지표 도착
  → ASG scaling activity 시작
  → EC2 부팅·user-data·CodeDeploy 완료
  → ALB target healthy
  → 실제 새 인스턴스가 요청을 받음
```

하네스는 각 시각과 단계별 소요 시간을 같은 타임라인에 표시한다. `process_cpu_usage`와 ASG의 `ASGAverageCPUUtilization`은 관찰 대상과 집계 방식이 다르므로 하나의 수치처럼 취급하지 않는다. t3.small의 CPU credit 상태와 burst 비용도 함께 기록한다. 그다음에야 지표 주기, health check/배포 준비 시간, 목표 CPU 60%, min/max/desired 정책 중 어디가 지연을 만들었는지 판단한다.

실시간 관측을 빠르게 하는 것과 새 서버를 빨리 usable하게 하는 것은 별개다. 예측 가능한 캠페인에서는 **사전 증설한 API 용량**을 기준 실험에 포함하고, CPU 기반 자동 확장은 예기치 못한 추가 부하를 처리하는 보조 수단으로 비교한다. 단, 사전 증설 효과도 비용·서버 개수와 함께 평가한다.

## 7. 성능 개선 실험의 우선순위

| 순서 | 가설·실험 | 기대 효과와 판정 포인트 | 주의점 |
| --- | --- | --- | --- |
| A | Consumer batch마다 남기는 INFO 로그를 샘플링/DEBUG로 바꿔 A/B | CPU·I/O와 API p95/p99, Consumer 처리량 | 장애 진단에 필요한 오류 로그는 유지 |
| B | Consumer concurrency·fetch 정책과 JDBC batch 옵션을 한 변수씩 비교 | DB 처리량·batch size·commit latency·RDS CPU | batch를 키우면 E2E 지연이 늘 수 있음 |
| C | API와 Bridge/Consumer를 별도 인스턴스 역할로 분리해 비교 | 유입 중 API 꼬리 지연과 Worker drain rate | 같은 총 자원 실험과 실제 비용 대비 실험을 분리 |
| D | Redis 건별 pop을 batch화 | Bridge CPU·Redis 왕복 횟수 감소 | pop 후 Kafka 발행 전 장애의 유실 창을 먼저 해결 |
| E | Worker backlog 기반 증설·backpressure | Queue 최대 크기·oldest age·복구 시간 제어 | 202 수치만 높이는 방향으로 설계하지 않음 |

현재 앱은 각 ASG 인스턴스에서 API, Bridge, Consumer가 함께 실행되고, Kafka listener concurrency는 토픽 파티션 수를 기준으로 설정된다. API/Worker 분리는 **가장 유력한 구조적 개선 가설**이지만 아직 검증 결과가 아니다. 역할 분리 시 Worker의 헬스체크·배포·스케일링 기준을 API와 구분하고, Kafka 파티션 10개와 Redis hot campaign·RDS가 다음 병목이 될 가능성을 함께 본다.

Redis batch pop은 처리량 개선 후보이지만, 기존에도 pop→Kafka 발행 사이에 프로세스 종료 시 유실 가능성이 있다. 단순 batch pop은 유실 범위를 키울 수 있으므로, 처리 중 목록/재시도 또는 동등한 복구 설계를 확정하고 장애 주입 테스트를 통과한 뒤 적용한다. 성능 개선보다 202 이후 수용된 이벤트의 복구 가능성을 우선한다.

## 8. 기존 MCP 모니터링은 어떻게 다룰까

`mcp-server`는 (1) AI가 호출하는 MCP 도구와 (2) 30초 폴링·Slack 알림을 한 프로세스에 담고 있다. Claude를 더 이상 쓰지 않는다고 서버 전체를 즉시 중단하면 알림도 사라질 수 있다.

1. 먼저 알림 규칙의 메트릭명·임계값·문구를 현재 앱과 대조한다. 예를 들어 앱 Redis Queue 상한은 **250만 건**인데 `mcp-server/config.py`에는 **150만 건 기준 70%/85%** 설명과 임계값이 남아 있다. 이 불일치를 바로잡기 전에는 위험도 문구를 신뢰하지 않는다.
2. Slack 알림과 정합성 감시를 MCP 호출 여부와 독립적으로 유지하고, 동등한 알림 수단으로 이전할지 결정한다. 이전 전에는 실제로 알림이 발송·복구되는지 시험한다.
3. `get_test_report`의 필요한 기능은 실행 ID와 고정된 지표 정의를 갖춘 하네스 리포트로 옮긴다.
4. MCP 호출자가 없고 알림·리포트 대체가 검증되면 MCP endpoint를 deprecated 처리한다. 기록과 설계 의도는 문서로 남긴다.

Grafana 패널은 사람이 이해하기 위한 화면, 알림은 무인 감시, 하네스 리포트는 재현 가능한 실험 증거다. 세 역할을 같은 것으로 보지 않는다.

## 9. 권장 작업 단위와 완료 조건

| 단계 | 우선 산출물 | 완료 조건 |
| --- | --- | --- |
| 0. 관측 신뢰성 | MCP 임계값·메트릭·문구 감사, 현재 문서 정리 | 알려진 Queue 상한 불일치 해소; 테스트 알림 확인 |
| 1. 하네스 최소 버전 | 짧은 `AGENTS.md`, 실행 manifest, k6/지표 수집, PASS/FAIL/INCONCLUSIVE 리포트 | 별도 실행 두 번을 동일 기준으로 비교 가능; 민감정보 제외 |
| 2. 현재 기준선 | capacity 단계별 실험, CPU→ASG→healthy 타임라인 | 지속 가능한 RPS 범위와 API p95/p99, Queue 기울기, 병목 후보 보고 |
| 3. 정확한 E2E 계측 | 접수 시각→DB 완료 시각, oldest-event age, 신규 INSERT 분리 | 202 지연과 최종 반영 지연을 별도 p95/p99로 제시 |
| 4. 작은 개선 A/B | 로그·Consumer/JDBC 설정을 개별 변경 | 동일 조건의 개선폭·변동폭·정합성·비용 비교 |
| 5. 구조 개선 A/B | API/Worker 분리와 사전 증설 실험 | 통합형 대비 API·E2E 지연, 지속 처리량, 비용 모두 비교 |
| 6. 복구·회귀 | Bridge 복구 설계, 장애 주입, 150만 건 integrity 재검증 | 유실·중복·재고 불일치 없음; 운영 문서·대시보드 갱신 |

단계 0과 1은 비교적 작은 변경으로 시작할 수 있다. 단계 3의 계측은 이벤트 스키마·타임스탬프 의미·중복 처리 방식을 검토해야 하므로 별도 설계가 필요하다. 단계 5는 배포/인프라 변경을 수반하므로 작은 코드 최적화와 한 PR에 섞지 않는다. 각 단계가 끝날 때 수치가 가설을 지지하지 않으면 다음 단계의 우선순위를 바꾼다.

## 10. 후속 과제: Kafka의 나머지 코드화와 볼륨 비용

Kafka는 **전혀 Terraform에 없는 상태가 아니다.** `infra/ec2.tf`가 브로커 EC2 3대와 각 8GiB gp3 root EBS를 정의하고, `env-up`/`env-down`은 이 인스턴스를 start/stop한다. 다만 브로커 설치·실행 방식, Kafka 설정·토픽 정책, 데이터 위치, 버전 변경 및 재생성 절차가 어디까지 재현 가능한지는 별도로 실사해야 한다. EC2 자원 정의와 **동일한 Kafka 클러스터를 새로 구축할 수 있는 능력**은 다른 수준의 코드화다.

중지된 EC2에는 인스턴스 사용료가 발생하지 않지만 EBS 저장 비용은 계속 발생한다. 반대로 EC2를 종료·재생성하면 `DeleteOnTermination` 설정에 따라 root volume이 삭제될 수 있다. 볼륨을 보존하면 저장 비용이 계속 발생하고, 스냅샷으로 옮겨도 스냅샷 저장 비용과 복구 절차가 남는다. 따라서 **Terraform으로 관리한다 = 안전하게 지웠다 다시 만들 수 있다 = 비용이 0이다**는 같은 말이 아니다. 정확한 현재 과금은 AWS Cost Explorer/청구서와 실제 볼륨·스냅샷 목록으로 확인한다.

| 순서 | 할 일 | 통과 기준 |
| --- | --- | --- |
| K1. 현황 실사 | 실제 EC2·EBS 매핑, `DeleteOnTermination`, Kafka 데이터 디렉터리, 브로커 ID/클러스터 ID, 버전·설정·토픽·복제/ISR, 부팅 방식 확인 | 코드와 실환경의 차이 및 계속 과금되는 자원 목록 작성 |
| K2. 재현성 확보 | OS/컨테이너·브로커 설정·토픽 설정을 코드로 관리하고 새 테스트 클러스터에서 기동 검증 | 기존 브로커를 건드리지 않고 새 환경에서 같은 정책 재현 |
| K3. 상태·비용 정책 결정 | 지속 보존(start/stop), 스냅샷 후 재생성, 완전 임시 클러스터 중 테스트 목적에 맞는 모드 선택 | 각 모드의 비용·RTO·데이터 유실 가능성 명시 |
| K4. 고립 복구 시험 | 소비 lag 0·Queue drain·DB 정합성 확인 후 테스트 데이터에서만 broker 종료→재생성→생산/소비·리더 선출 확인 | 이벤트 유실·중복·ISR 설정 오류 없이 복구; 실패 시 롤백 가능 |

**현재 기본 선택은 브로커 start/stop 유지**다. #95 성능 기준선을 잡는 도중 Kafka 브로커를 재생성하면 성능 변수와 데이터 위험이 동시에 바뀐다. 장기적으로는 Kafka의 설정/부트스트랩을 코드화하되, EBS까지 없애는 `env-down`은 테스트 클러스터를 완전히 버려도 되는지와 재구축 검증이 끝난 뒤 별도 작업으로 결정한다. 특히 승인된 `202` 이벤트가 아직 Kafka 또는 Consumer에 남아 있다면 Kafka 데이터 디스크를 비용 때문에 삭제해서는 안 된다.

## 11. 다음 첫 작업 제안

**첫 PR은 ‘하네스 최소 버전 + 관측 기준 정리’로 제한**한다. 실환경을 켜지 않고도 가능한 작업부터 한다: 저장소 지침 초안, 결과 manifest 형식, 리포트 계산·테스트, 현재 메트릭/임계값 대조. 다음에 비용을 들이는 테스트를 할 때 그 하네스로 첫 기준선을 만든다. 이를 통해 #95의 API/Worker 분리 여부를 느낌이 아니라 동일 조건의 증거로 결정한다.

## 참고

- 프로젝트 [이슈 #95](https://github.com/hskhsmm/1milion-campaign-orchestration-system/issues/95)
- `docs/reports/2026-09-20-aws-recovery-150m-validation.md`
- `stress-test/TEST_GUIDE.md`, `stress-test/run-test.sh`
- `ops/README.md`, `infra/asg.tf`
- `infra/ec2.tf`, `ops/playbooks/env-up.yml`, `ops/playbooks/env-down.yml`
- [OpenAI Docs: AGENTS.md](https://learn.chatgpt.com/docs/agent-configuration/agents-md)
- [OpenAI: AGENTS.md와 Skill을 간결하게 유지하는 방법](https://developers.openai.com/blog/rethinking-skills-and-prompts-for-gpt-6-astra)
- [AWS: 중지된 EC2와 EBS 과금](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-instance-lifecycle.html)
- [AWS: EC2 종료 시 EBS 보존 여부](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/preserving-volumes-on-termination.html)
