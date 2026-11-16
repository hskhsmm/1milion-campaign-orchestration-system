# 정합성 테스트 하네스

`combined-fixed` 공용 앱 3대에서 같은 절차로 **사전 점검 → 새 캠페인 생성 → k6 `shared-iterations` → 배출 확인 → 증거 수집 → 판정**을 실행한다. [부하 테스트 가이드](../TEST_GUIDE.md)의 수동 절차와 기존 k6 시나리오를 사용한다.

## AWS 테스트 환경에서 실행

이미 켜진 테스트 환경에서 실행한다. 로컬 운영 PC에는 Git·AWS CLI·curl·Python 3.9+·Bash가 필요하다. terraform-mcp의 SSM·Docker·k6·systemd가 동작하고, 부하 발생기 저장소는 지정한 커밋으로 맞춘다. `EXPECTED_APP_SHA`에는 **실제 배포한 앱 커밋**, `LOADGEN_SHA`에는 **부하 발생기가 checkout한 커밋**을 넣는다. 후자를 생략하면 로컬의 `origin/main`을 사용한다. 하네스 자체가 들어 있는 브랜치와 두 값을 별도로 지정할 수 있다.

첫 실환경 확인은 아래처럼 새 캠페인에 소량만 보낸다. `REDIS_GUARD_BYTES`는 현재 Redis primary의 메모리 한도와 평상시 사용량을 보고 실행자가 정한다. `START_CONDITION`은 `cold` 또는 `warmed`를 기록한다. 예열을 비교하려면 팀이 정한 동일한 예열을 별도 캠페인에서 끝내고 실행한다.

```bash
CONFIRM_RUN=yes \
EXPECTED_APP_SHA="배포한_전체_SHA" \
LOADGEN_SHA="terraform-mcp가_checkout한_전체_SHA" \
START_CONDITION=warmed \
REDIS_GUARD_BYTES="샤드당_중단_기준_바이트" \
TOTAL_REQUESTS=1000 MAX_VUS=20 DURATION=60 \
bash stress-test/harness/run.sh
```

- 사전 점검은 서울 리전 프로젝트 계정, 부하 발생기 Git SHA·파일 해시, ECR의 배포 이미지 digest, 앱 컨테이너 역할·상태, 고정 ASG 용량, ALB healthy 대상을 확인한다. Kafka lag와 Hikari pending이 모두 0이어야 부하를 시작한다.
- 부하 중 각 Redis primary의 `INFO memory`를 확인한다. 한도를 넘거나 표본을 읽을 수 없으면 해당 k6를 중단하고 이미 접수된 요청의 배출을 기다린다. 표본 사이에 사용량이 더 올라갈 수 있으므로 한도에 여유를 두고 사용한다.
- 종료 후 Queue·in-flight·Kafka lag가 두 번 연속 0인지 확인한다. 배출 대기 기본값은 900초(`DRAIN_TIMEOUT`)다. 원격 실행은 고유 systemd 서비스로 관리하며, 운영 PC의 중단 요청은 그 서비스에만 전달한다.
- `run.json`, k6, Redis 표본, Prometheus, 최종 상태·DB SELECT·DLQ 건수, 정합성 dry-run, ASG 활동을 저장한다. 자동화는 전후 앱 컨테이너 교체 여부와 부하 발생기 버전 변경도 확인한다.
- 환경 기동과 종료는 별도다. 결과를 확인한 뒤 기존 절차로 `env-down`을 실행한다. 원격 증거는 terraform-mcp의 `/var/tmp/campaign-harness/<RUN_ID>`에, 로컬 증거는 Git에서 제외되는 `evidence/<RUN_ID>`(또는 `EVIDENCE_ROOT`)에 남는다. 이 경로에는 해당 실행에서 얻은 증거가 포함되므로 선별해서 공유한다.

## 판정

`verdict.md`는 사람이 읽는 결과, `verdict.json`은 후속 도구가 읽을 결과다. 종료 코드는 `PASS` 0, `FAIL` 1, `INCONCLUSIVE` 2다. 운영자 중단은 130으로 종료될 수 있다.

| 결과 | 예시 |
| --- | --- |
| PASS | 목표 요청 수 완료, k6 202·Kafka 발행·상태 API·DB SUCCESS 일치, DB 순번 1~N 및 중복·DLQ·배출 잔량 0 |
| FAIL | 완료된 실행에서 확인한 불일치, 중복 또는 오류 |
| INCONCLUSIVE | Redis 가드 중단, 필수 데이터 누락, 목표 부하 미달, 컨테이너 변경 또는 카운터 초기화 |

재고 0은 목표 요청 수를 모두 접수한 실행에서 확인한다. 목표 미달이어도 확인된 Kafka·DB 건수 불일치는 `FAIL`로 기록한다.

k6 클라이언트 p95/p99는 Grafana의 서버 p95/p99와 관측 구간이 다르다. k6 기본 p95 1초 기준의 결과는 정합성과 함께 별도 항목으로 표시한다. `PASS`는 최종 건수와 순번 범위의 검사 결과이며, 개별 요청의 도착 순서까지 재구성하지는 않는다. Kafka 파티션 수, RDS·Redis 사양, 로그 수준을 비교하려면 실험 기록에 함께 적는다.

`combined-fixed`는 3대 고정 모드다. 최대 4대 ASG scale-out, 분리 모드, 지속 RPS, 장애 주입 검증은 해당 시나리오의 기존 절차를 사용한다.

## 저장된 증거를 로컬에서 판정

```bash
python3 stress-test/harness/verdict.py evidence/<RUN_ID>
python3 -m unittest discover -s stress-test/harness/tests
```

`verdict.py`는 AWS나 Docker 없이 실행된다. 과거 실행에서 발행 건수·종료 in-flight 등 필수 증거가 빠졌다면 `INCONCLUSIVE`로 남긴다. 구현은 팀의 기존 `feat/evidence-harness` 판정 초안을 바탕으로 확장했다.
