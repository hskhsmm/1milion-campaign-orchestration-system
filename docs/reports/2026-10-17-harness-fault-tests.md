# 실행·판정 하네스 검증: 정상 실행과 고장 시험

> 실행: 2026-10-17, AWS `ap-northeast-2` 테스트 환경
> 기준 코드·배포 이미지: `89902c3d8c86164fc03afef3f04738b65dab68ec`
> 대상: [`stress-test/harness/`](../../stress-test/harness/README.md) (`run.sh`, `precheck.sh`, `collect.sh`, `verdict.py`)
> 목적: 하네스가 정상 실행에 오탐 없이 판정하는지, 알려진 고장을 FAIL 또는 INCONCLUSIVE로 잡는지 확인한다.

## 배경

[성능·관측 하네스 계획](../plans/performance-harness.md) 4.2와 11장은 실행 래퍼·리포트를 **실제 테스트에서 반복되는 수작업과 누락되는 증거가 확인된 뒤에** 만들기로 했다. 같은 날 [500·750·1,000 RPS 탐색](2026-10-17-capacity-equilibrium.md)을 수동으로 세 번 실행하면서 다음을 확인했다.

- 실행마다 같은 수집 명령(k6 회수, Prometheus 조회 9개, DB SELECT, 상태 API, 로그)을 반복했다.
- Queue 감시 시작이 한 번 늦었다(실행 권한 문제로 재실행).
- 앱 로그 전체 회수가 실행당 10분 넘게 걸렸다.
- 10-15 검증에서는 부하 발생기 저장소가 구버전(`fbbbc11`)이었다.

## 판정 규칙

판정 기준은 [부하 테스트 가이드](../../stress-test/TEST_GUIDE.md) 8.3을 따른다. 규칙과 조건은 [하네스 README](../../stress-test/harness/README.md#판정)에 있다. 탐색에는 PASS를 내지 않고, 문제가 없으면 `VALID`를 낸다.

장애 실행은 `PURPOSE=fault`로 표시하고 잡혀야 할 항목을 미리 적는다. 판정기는 그 항목이 기대 상태인지 보고 `FAULT_DETECTED`(잡음) / `FAULT_UNEXPECTED`(잡았지만 다른 항목도 이상) / `FAULT_MISSED`(놓침)를 낸다. 장애 실행의 FAIL이 실제 시스템 실패 기록과 섞이지 않게 하기 위해서다.

## 로컬 시험

`python3 -m unittest discover -s stress-test/harness/tests` — 40개 통과 (Python 3.9.6).

| 구분 | 시험 | 기대 |
| --- | --- | --- |
| 정상 | 10-17 capacity 500·750·1,000 RPS 실제 증거 | VALID, FAIL·INCONCLUSIVE 항목 0 |
| 정상 | 10-17 `run.sh` integrity 1만 건 실제 증거 (정합성 dry-run 포함) | PASS |
| 과거 사건 | 부하 발생기 구버전 스크립트 (10-15 #105 검증) | FAIL (`pre_version`) |
| 과거 사건 | AMI에 남은 구버전 컨테이너 ([AMI 후속 조치](2026-10-03-ami-container-remediation.md)) | FAIL (`pre_containers`) |
| 과거 사건 | Gauge 0인데 실제 Queue 130만 건 고립 ([좀비 컨테이너](2026-09-28-zombie-container-root-cause.md), 재구성) | FAIL (`queue_final`) |
| 과거 사건 | 202 접수 후 DB 미도달 (같은 문서, 재구성) | FAIL (`db_counts`) |
| 규칙별 | DB 중복, drain 시간 초과, 종료 후 lag, integrity 5xx·요청 미달·재고 잔량 | FAIL |
| 규칙별 | 필수 파일 8종 각각 누락, `run.json` 누락, 잘린 JSON, scale-out, `dropped_iterations`, Queue 감시 지연, 부하 전 lag, 수집 오류, 사전 점검 중단, capacity 최종 검증 기준 없음 | INCONCLUSIVE |
| 규칙별 | capacity 최종 검증 기준 충족 / 미달 | PASS / FAIL |
| 장애 판정 | 기대 항목이 잡힘 / 다른 항목도 실패 / 흔적 없음 / 기대 미기재 / 사전 점검 중단 | FAULT_DETECTED / FAULT_UNEXPECTED / FAULT_MISSED / FAULT_MISSED / FAULT_DETECTED |

- 정상 capacity fixture의 `run.json`, `precheck.json`, `drain.json`, `scaling.json`은 수동 실행 당시 없던 파일이라 `manifest.md` 기록에서 재구성했다. 다른 파일은 원본이다.
- **시험이 잡은 판정기 오류**: 처음 작성한 판정기는 k6 결과가 없으면 DB 건수 비교에서 FAIL을 냈다. 비교 대상이 없는 것이므로 INCONCLUSIVE로 고쳤다.
- **시험이 규칙을 실제로 지키는지 확인**: 판정기의 Queue 잔량 규칙을 일부러 끄면 130만 건 고립 시험이 실패한다.

## 실환경 시험

| # | 시나리오 | RUN_ID | 기대 | 결과 |
| --- | --- | --- | --- | --- |
| A | 정상 integrity 1만 건 (캠페인 78) | `20261017T114046Z-harness-normal-integrity` | PASS | **PASS** |
| B | 부하 발생기를 `fbbbc11`로 바꾸고 실행 | `20261017T114307Z-fault-stale-loadgen` | 부하 전 중단 (`pre_version=FAIL`) | **사전 점검에서 중단, FAULT_DETECTED** |
| C | 부하 중 앱 1대 35초 정지 | — | `k6_errors=FAIL` | **미실행** (아래) |
| D | 수집 25초 만에 강제 종료 | `20261017T114407Z-fault-collect-cut` | 못 받은 항목 INCONCLUSIVE | **INCONCLUSIVE, FAULT_DETECTED** |
| E | 수동 수집한 1,000 RPS 실행을 새 수집기로 다시 수집 | `20261017T114453Z-recollect-capacity-1000` | 수동 수치와 같음 | **같음** |
| A2 | 수집기 수정 뒤 정상 integrity 재실행 (캠페인 79) | `20261017T114915Z-harness-normal-integrity-2` | PASS | **PASS** |
| F | 정상 capacity 200 RPS 60초 유지 (캠페인 80) | `20261017T115358Z-harness-normal-capacity` | VALID | **VALID** |
| H | capacity 1,250 RPS 10분 유지 (캠페인 82), 하네스로 처음부터 끝까지 | `20261017T121831Z-capacity-1250` | VALID | **VALID** |
| I | 2,500 RPS 60초로 Queue를 쌓고 drain 대기 20초로 제한 (캠페인 83) | `20261017T123323Z-fault-drain-timeout` | `drain`·`queue_final` FAIL | **FAULT_DETECTED** (처음 기대값 오류로 FAULT_MISSED, 아래) |
| G1 | 사전 점검 중 오류 (존재하지 않는 리전 지정) | `20261017T121450Z-fault-precheck-error` | 판정 없이 끝나지 않음 | **INCONCLUSIVE**, 중단 단계 기록 |
| G2 | k6 대기 중 `run.sh`에 SIGTERM (캠페인 81, 3만 건) | `20261017T121511Z-fault-run-interrupted` | `collect`·`drain` INCONCLUSIVE | **FAULT_DETECTED** |

B와 D는 처음 실행할 때 장애 표시 기능이 없었다. 기능을 추가한 뒤 두 실행의 `run.json`에 장애 내용과 기대 항목을 적고 같은 증거로 다시 판정했다.

**A. 정상 실행**: k6 202, 상태 API, DB SUCCESS가 모두 10,000건이었고 중복 0, Queue 최대 8,154건, drain 2초였다. 사전 점검은 약 19초 걸렸다.

**B. 부하 발생기 구버전**: 부하 발생기 저장소를 `fbbbc11`로 바꾸자, 사전 점검이 `k6-load-test.js`·`k6-tps-test.js`·`run-test.sh` md5 불일치를 잡고 캠페인 생성 전에 멈췄다(캠페인 79가 생성되지 않음을 확인). 시험 뒤 저장소를 main(`89902c3`)으로 되돌렸다.

**C. 앱 1대 정지**: Consumer만 멈추는 기능은 없다. 앱 인스턴스에서 DB 연결을 차단하는 방법은 Consumer가 이벤트마다 DLQ 발행과 Slack 알림을 보내 알림이 대량 발생하므로 쓰지 않았다. 대안으로 앱 컨테이너 1대를 35초 `docker pause`(ALB unhealthy 판단 50초 미만, 인스턴스 안에서 자동 해제)하려 했으나, 실환경 컨테이너를 멈추는 원격 명령이라 실행 권한 확인 단계에서 보류됐다. **이 시나리오는 검증하지 않았다.**

**D. 수집 중단**: 회수하지 못한 Redis·Kafka·DB·scaling 증거가 각각 누락으로 잡혀 PASS가 나오지 않았다. 처음 시도는 macOS에 `timeout` 명령이 없어 수집 자체가 시작되지 않았으므로 무효로 하고 백그라운드 실행 후 `kill`로 다시 했다.

**E. 수동 결과와 대조** (안정 구간 19:44:30~19:53:30 KST 평균)

| 지표 | 수동 수집 | 새 수집기 |
| --- | ---: | ---: |
| API 202 | 1,000.0건/s | 1,000.0건/s |
| Bridge 발행 | 1,000.3건/s | 1,000.2건/s |
| DB 성공 경로 | 1,000.1건/s | 1,000.1건/s |
| 서버 p95 / p99 | 8.0 / 16.8 ms | 8.0 / 16.8 ms |
| Kafka lag 평균 / 최대 | 32.6 / 113 | 33.1 / 113 |
| Queue Gauge 평균 / 최대 | 65.5 / 149 | 65.0 / 149 |
| DB SUCCESS, 중복 | 618,249, 0 | 618,249, 0 |

평균의 작은 차이는 조회 시작 시각이 달라 10초 표본 위치가 다르기 때문이다. `k6-summary.json`은 바이트 단위로 같았다. Redis 감시 기록은 이미 종료된 감시라 다시 받을 수 없어 원본을 복사했다.

**H. capacity 10분 실행**: 하네스로 10분 유지 capacity를 처음 실행했다. 탐색 결과로도 쓸 수 있다(안정 구간 21:20:30~21:29:30 KST).

| 항목 | 값 |
| --- | --- |
| k6 | 202 772,624건, 5xx·실패 0, `dropped_iterations` 0 |
| 접수 / Bridge / DB 성공 경로 | 1,250.0 / 1,250.0 / 1,249.9건/s |
| 서버 p95 / p99 | 5.4 / 11.8 ms |
| Redis `LLEN` 평균 / 최대 / 기울기 | 60 / 793건 / −0.021건/s |
| Kafka lag 최대 / 종료 후 | 118 / 0 |
| DB SUCCESS, 중복 | 772,624, 0 |
| 앱 CPU | 두 대 약 50% |
| 실행 중 변경 | 없음 (scaling activity 없음) |

1,250 RPS에서도 Queue가 쌓이지 않았다. [500·750·1,000 RPS 탐색](2026-10-17-capacity-equilibrium.md)과 합치면 Queue가 쌓이기 시작하는 지점은 **1,250~2,000 RPS 사이**로 좁혀진다. 단계당 1회 실행이며 지속 가능 처리량을 확정하지 않는다.

**I. drain 시간 초과**: 2,500 RPS 60초 동안 202 175,373건이 접수됐고 Redis `LLEN`은 최대 106,813건까지 쌓였다. drain 대기 20초(실제 27초) 마감 시점에 `LLEN` 64,156, lag 44가 남아 `drain`과 `queue_final`이 FAIL이었다. `dropped_iterations` 0, scaling activity 없음.
- 처음 기대값에는 `db_counts=FAIL`도 넣었으나 실제는 OK여서 FAULT_MISSED가 나왔다(`verdict-initial.md`). DB 조회는 수집 후반(21:37:22)이었고 drain 마감(21:35:41)부터 그 사이에 Queue가 비워져 DB 건수가 접수 수와 같아졌다. 하네스가 놓친 것이 아니라 기대값이 틀렸으므로 `db_counts`를 빼고 같은 증거로 다시 판정했다(FAULT_DETECTED).
- 이 결과는 DB 건수 검사가 "수집 시점의 최종 상태"를 본다는 뜻이다. drain 마감 시점의 미반영 건수는 `drain`·`queue_final`이 잡는다.

**G. 실행 스크립트 중단**: 처음 작성한 `run.sh`는 중간에 실패하면 `verdict.md` 없이 끝나고 Queue 감시 컨테이너가 남았다. 종료 처리(`trap`)를 추가해, 어느 단계에서 끝나든 중단 단계를 `collect-errors.txt`에 남기고, 부하를 시작한 뒤라면 가능한 증거를 수집(감시 컨테이너 종료 포함)한 뒤 판정하게 했다.
- G1: 사전 점검이 AWS 오류로 실패하자 INCONCLUSIVE와 "'사전 점검' 단계에서 중단"이 남았다.
- G2: k6 대기 중 SIGTERM을 보내자 증거를 수집하고 감시를 종료했다. 부하 발생기에 남은 k6는 없었다(3만 건이 이미 끝남). 처음 적은 기대값에 `drain`을 빠뜨려 FAULT_UNEXPECTED가 나왔다. drain 단계 전에 중단했으므로 drain 누락은 기대 결과이며, 기대값을 고쳐 같은 증거로 다시 판정했다(`run.json`의 `fault.note`). 이때 끝 시각이 없는 실행에서 scaling 검사가 오류를 내는 판정기 버그도 찾아 고쳤다.

## 대조에서 찾아 고친 수집기 오류

| 문제 | 원인 | 조치 |
| --- | --- | --- |
| Prometheus 결과 JSON이 중간에 잘림 | SSM 명령 출력은 24,000자에서 잘린다. 긴 구간 조회 결과가 이를 넘었다 | `ssm_fetch`(분할 전송)로 받고, JSON이 온전하지 않으면 수집 오류로 기록 |
| 다시 수집하면 조회 구간이 달라짐 | 조회 끝을 "지금"으로 잡음 | drain 종료 2분 뒤로 고정 |
| 앱 ERROR가 0건이면 수집 실패로 처리 | `grep`이 일치 없음을 실패 코드로 반환 | 일치 없음을 정상으로 처리 |
| 앱 로그도 24,000자 초과 가능 | 위와 같음 | `ssm_fetch`로 받음 |

수정 뒤 A2를 처음부터 다시 실행해 수집 오류 없이 PASS를 확인했다.

**F. capacity 경로**: k6 setup이 만든 캠페인 ID를 로그에서 읽어 setup 9초 뒤(워밍업 30초 안)에 Queue 감시를 시작했다. k6 202, 상태 API, DB SUCCESS가 모두 16,249건이었고 `dropped_iterations` 0, Queue 최대 22건, drain 3초였다.

## 보고서 수치 초안 (`report.py`)

10-17 보고서 두 편은 증거를 손으로 계산해 썼다. 매번 같은 수치를 옮겨 적는 작업이 반복되고 옮겨 적기 오류 위험이 있어, 증거에서 수치 표를 만드는 `report.py`를 추가했다. `run.sh`가 판정 뒤 `report-draft.md`를 만든다. 해석·결론·한계는 쓰지 않는다.

1,250 RPS 실행(H)으로 확인한 결과, 안정 구간 처리율(1,250.0 / 1,250.0 / 1,249.9건/s), 서버 p95/p99(5.4 / 11.8 ms), `LLEN` 평균·최대·기울기(60 / 793 / −0.021건/s), lag 최대(118)가 손으로 계산한 값과 같았다.

## 새 에이전트 시험: 하네스를 스스로 찾아 쓰는가

머지 후 "부하 테스트 해 줘" 요청에서 하네스가 실제로 쓰이는지 확인하려고, 하네스를 언급하지 않고 새 에이전트에게 "integrity 1만 건을 저장소 지침대로 실행하고 보고서를 써 달라"고만 요청했다(커밋·데이터 수정·env-down 금지).

- 읽은 순서: `AGENTS.md` → `docs/README.md` → TEST_GUIDE 4.3·8장 → 하네스 README → 스크립트. `run.sh`로 실행했고 `run-test.sh`를 직접 실행하지 않았다.
- 결과: PASS (RUN_ID `20261017T130330Z-integrity`, 캠페인 84). 1만 건 일치, 중복 0, drain 11초. 보고서 수치는 `report-draft.md` 표를 그대로 옮겼다(직접 계산 없음).
- 비용: 약 10.5만 토큰, 도구 20회, 5분 30초.

에이전트가 찾은 문제와 조치:

| 문제 | 조치 |
| --- | --- |
| TEST_GUIDE 8.2는 최종 검증에 정합성 dry-run을 필수로 두는데 하네스는 없이 PASS를 냄 | `collect.sh`에 dry-run 수집 추가, 최종 검증에서 없으면 INCONCLUSIVE. 실환경 확인: `20261017T131338Z-harness-integrity-consistency` PASS, `anomalyCount 1`(`CLOSED_REDIS_RESIDUE`, WARNING)을 NOTE로 기록 |
| 짧은 integrity(실제 부하 3초)에서 "안정 구간" 처리율·p95가 1분 평균으로 희석되고 Bridge 발행률이 0으로 나옴 | 구간이 60초 미만이면 `report.py`가 Prometheus 표를 만들지 않고 이유를 적음 |
| 하네스를 실행한 커밋이 기록되지 않음 | `run.json`에 `harness_commit`, `harness_dirty` 기록 |
| TEST_GUIDE 8.2의 앱 로그 전체 필수와 하네스 수집(ERROR/WARN만)이 다름 | 8.2에 하네스 수집 범위와 전체 로그가 필요할 때의 방법을 적음 |
| 문서의 WSL 경로 전제(운영 PC는 macOS) | 이번 범위 밖. 후속 과제 |

`CLOSED_REDIS_RESIDUE`는 10-15 #105 검증(캠페인 72)과 이번 실행에서 모두 정상 종료 캠페인에 나왔다. 의도된 동작인지 확인이 필요하다.

## 검증 상태

| 영역 | 상태 | 근거 |
| --- | --- | --- |
| 정상 경로 (integrity·capacity, 오탐 없음) | 검증 | A, A2, F, H |
| 사전 점검 중단 (부하 발생기 버전) | 검증 | B |
| 수집 중단·스크립트 중단 시 PASS 금지 | 검증 | D, G1, G2 |
| drain 시간 초과 감지 | 검증 | I |
| 수집 수치의 정확성 | 검증 | E (수동 수집과 일치) |
| 앱 이미지 불일치, Queue 고립, DB 미도달, 중복 | fixture로만 검증 | 로컬 시험 (일부 재구성) |
| **부하 중 시스템 장애(앱 정지) 감지** | **미검증** | C 미실행 |
| 실행 중 scale-out 감지 | fixture로만 검증 | 실환경에서 scale-out이 일어난 실행 없음 |
| `dropped_iterations` 발생 | fixture로만 검증 | 2,500 RPS에서도 0 |

각 시나리오는 1회씩 실행했다.

## 한계와 남은 일

- **C(부하 중 앱 정지)는 검증하지 않았다.** 실환경 컨테이너를 멈추는 원격 명령이 자동 승인 단계에서 두 번 보류됐다. 진행하려면 실행 권한을 명시적으로 허용해야 한다.
- 판정은 이미 겪은 실패에 대한 규칙만 가진다. 새로운 종류의 실패를 겪으면 fixture와 규칙을 추가한다.
- 접수→DB 반영 지연은 측정하지 않는다.
- capacity 최종 검증은 운영 목표(`CRITERIA_FILE`)가 합의되기 전까지 INCONCLUSIVE다.
- 시험 캠페인 78·79는 integrity로 정상 종료됐다. 시험 캠페인 81(integrity, 중단 시험)은 정상 종료됐다. capacity 캠페인 75~77·80·82·83은 OPEN·active Set에 남아 있다.
