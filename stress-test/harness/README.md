# 부하 테스트 실행·판정 하네스

[부하 테스트 가이드](../TEST_GUIDE.md) 8장의 기록·판정 절차를 스크립트로 고정한 것이다. 사람이 순서를 기억하지 않아도 같은 순서로 실행되고, 증거가 빠지면 PASS가 나오지 않는다.

```text
사전 점검 ─(버전·이미지 불일치면 중단)→ Queue 감시 시작 → k6 → drain 대기 → 증거 수집 → 판정 → 수치 초안
```

## 구성

| 파일 | 역할 | 실환경 영향 |
| --- | --- | --- |
| `run.sh` | 위 순서 전체를 실행 | 캠페인 생성, 부하 발생 |
| `precheck.sh` | 부하 발생기 커밋·스크립트 md5, 앱 컨테이너 이미지, ASG·ALB, lag, Hikari, active Set 기록 | 읽기 전용 |
| `collect.sh` | k6 결과, Redis 감시 기록, Prometheus 구간 조회, 상태 API, DB 직접 조회(SELECT), scaling activity, 앱 ERROR/WARN 로그 | 읽기 전용 (감시 컨테이너 종료) |
| `verdict.py` | 증거 디렉터리를 읽어 `verdict.json`, `verdict.md` 작성 | 없음 (로컬) |
| `report.py` | 증거에서 보고서 수치 초안 `report-draft.md` 작성 (해석은 비워 둠) | 없음 (로컬) |
| `tests/` | 판정기 시험: 실제 정상 실행 3건과 고장 시나리오 | 없음 (로컬) |

`run.sh`는 데이터 삭제, 배포, Terraform 변경, `env-down`을 하지 않는다. 비용이 드는 실행이므로 대상 환경을 확인한 사람이 직접 시작한다.

## 사용법

운영 PC의 저장소 루트에서 실행한다. AWS 자격 증명과 `git fetch origin`이 필요하다(부하 발생기를 `origin/main`과 비교한다).

```bash
# 정합성 최종 검증 (TEST_GUIDE 4.4 기준)
MODE=integrity PURPOSE=final TOTAL_REQUESTS=10000 MAX_VUS=200 DURATION=60 \
  bash stress-test/harness/run.sh

# 지속 처리량 탐색 (PASS 대신 VALID)
MODE=capacity PURPOSE=explore TARGET_RPS=1000 STEADY_SECONDS=600 \
  bash stress-test/harness/run.sh
```

결과는 `evidence/<RUN_ID>/`에 모인다(Git 제외). 종료 코드는 0(PASS·VALID·FAULT_DETECTED), 1(FAIL·FAULT_MISSED), 2(INCONCLUSIVE·FAULT_UNEXPECTED), 3(사전 점검 실패로 중단)이다. 수집이나 판정만 다시 하려면 `collect.sh <증거 디렉터리>`, `verdict.py <증거 디렉터리>`를 직접 실행한다.

## 보고서 수치 초안

`run.sh`는 판정 뒤 `report.py`로 `report-draft.md`를 만든다. 조건, k6, 안정 구간 처리율·p95/p99·Queue Gauge·lag·Hikari·CPU, 직접 `LLEN`의 평균·최대·기울기, drain, DB 직접 조회를 출처 파일과 함께 표로 쓴다. **해석·결론·한계·다음 계획은 쓰지 않는다.** 사람이나 에이전트가 표를 보고 `docs/reports/`에 보고서를 쓴다. 수치를 옮겨 적지 말고 초안의 표를 그대로 가져온다.

- 안정 구간 기본값: capacity는 워밍업 끝 + 60초(1분 rate 창) ~ 부하 끝 − 15초, integrity는 부하 전체
- 다른 구간: `python3 stress-test/harness/report.py evidence/<RUN_ID> 21:20:30 21:29:30`
- 10-17 1,250 RPS 실행으로 손으로 계산한 값과 같은지 확인했다.

## 판정

| 결과 | 조건 |
| --- | --- |
| FAIL | 부하 발생기 스크립트·앱 이미지 불일치, 종료 후 Queue `LLEN` 또는 Kafka lag 잔량, drain 시간 초과, k6 202·상태 API·DB 건수 불일치, DB 중복. integrity는 k6 실패·5xx·재고 잔량도 포함 |
| INCONCLUSIVE | 필수 증거 누락·파싱 실패, 수집 오류, 실행 중 scaling activity, `dropped_iterations` > 0, Queue 감시가 늦게 시작, 부하 전 lag·Hikari·ALB 이상, 사전 점검 중단, capacity 최종 검증의 기준(`CRITERIA_FILE`) 없음 |
| PASS | 최종 검증(`PURPOSE=final`)에서 위에 해당하지 않음 |
| VALID | 탐색(`PURPOSE=explore`)에서 위에 해당하지 않음. 관측값을 비교에 쓸 수 있다는 뜻이며 성능 합격이 아니다 |

- FAIL이 하나라도 있으면 FAIL, 없고 INCONCLUSIVE가 있으면 INCONCLUSIVE다.

### 일부러 장애를 낸 실행 (`PURPOSE=fault`)

장애 테스트에서는 FAIL이 나와야 하네스가 제대로 동작한 것이다. 일반 실행의 FAIL과 섞이지 않도록 실행 전에 장애 내용과 **잡혀야 할 항목**을 적는다. 장애 주입 자체는 `run.sh` 밖에서 사람이 한다.

```bash
MODE=integrity PURPOSE=fault TOTAL_REQUESTS=60000 MAX_VUS=200 \
FAULT_DESC="부하 중 앱 1대 35초 docker pause" FAULT_EXPECT="k6_errors=FAIL" \
  bash stress-test/harness/run.sh
```

| 결과 | 뜻 |
| --- | --- |
| FAULT_DETECTED | 적어 둔 항목이 모두 기대 상태이고 다른 항목은 정상. 하네스가 장애를 잡았다 |
| FAULT_UNEXPECTED | 적어 둔 대로 잡았지만 다른 항목도 FAIL·INCONCLUSIVE. 장애가 예상보다 넓게 퍼졌는지 확인한다 |
| FAULT_MISSED | 적어 둔 항목 중 하나라도 기대 상태가 아님. 하네스가 장애를 놓쳤다 |

`verdict.md` 첫머리에 "장애 주입 실행"이 표시된다. 이 실행은 성능·정합성 기록이나 실행 간 비교에 쓰지 않는다.
- capacity의 5xx·지연은 탐색에서 관측값(NOTE)으로만 남긴다. 최종 검증에서는 `CRITERIA_FILE`의 사전 합의 기준으로 판정한다.
- 판정은 Grafana Gauge가 아니라 Redis 직접 `LLEN`, `consumer_db_committed_total`이 아니라 DB 직접 조회를 사용한다.

## 시험

```bash
python3 -m unittest discover -s stress-test/harness/tests
```

- `fixtures/normal-capacity-*`: 2026-10-17 실환경 500·750·1,000 RPS 실행의 실제 증거. 오탐 없이 VALID여야 한다. 당시 없던 `run.json` 등은 `manifest.md`에서 재구성했다(`reconstructed` 항목).
- 고장 시나리오는 정상 fixture를 복사해 한 가지만 바꾼다. 과거 사건(구버전 k6, AMI 구버전 컨테이너, Gauge 0인데 Queue 130만 건 고립, 202 접수 후 DB 미도달)은 출처 문서를 시험에 적었다.
- 판정 규칙을 바꾸면 시험도 함께 바꾸고, 바꾼 이유를 PR에 적는다.

실환경 고장 시험 결과는 [하네스 고장 시험 보고서](../../docs/reports/2026-10-17-harness-fault-tests.md)에 있다.

## 한계

- 접수→DB 반영 지연은 측정하지 않는다(계측 없음).
- 처음 보는 종류의 실패는 규칙이 없으면 잡지 못한다. 새 사건을 겪으면 fixture와 규칙을 추가한다.
- 다른 캠페인이 active Set에 남아 있으면 NOTE로만 기록한다.
- k6 종료는 15초 간격으로 확인하므로 `load_end_epoch`은 최대 15초 늦다.
