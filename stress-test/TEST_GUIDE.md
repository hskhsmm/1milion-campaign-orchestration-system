# 부하 테스트 및 정합성 검증 가이드

> 현재 기준: Redis-first v3, ASG 앱 인스턴스, Kafka 10파티션, 비동기 DB 저장

## 1. 테스트 목적을 먼저 구분한다

| 목적 | k6 executor | 실행 모드 | 답할 수 있는 질문 |
| --- | --- | --- | --- |
| 정확한 총량과 정합성 검증 | `shared-iterations` | `integrity` | 150만 요청이 유실·중복 없이 DB에 반영되는가 |
| 지속 가능한 처리량 탐색 | `ramping-arrival-rate` | `capacity` | 일정 유입률을 SLO와 Queue 안정 조건 아래 얼마나 오래 버티는가 |

두 결과를 하나의 TPS로 섞지 않는다.

- `integrity` 결과의 API TPS는 정해진 요청을 가능한 빠르게 밀어 넣은 Spike 처리량이다.
- `capacity` 결과의 sustainable TPS는 `dropped_iterations=0`, SLO 충족, Queue 비증가 조건을 동시에 만족하는 최대 arrival rate다.
- API `202 Accepted` TPS와 DB commit TPS는 서로 다른 구간의 지표다.

## 2. 현재 스크립트

| 파일 | 현재 용도 |
| --- | --- |
| `run-test.sh` | 환경과 테스트 모드를 선택하는 공통 진입점 |
| `resolve-base-url.sh` | 현재 AWS 계정의 active ALB DNS를 조회. ALB가 없으면 실패 |
| `k6-load-test.js` | 정확한 요청 수 기반 Spike/정합성 테스트 |
| `k6-tps-test.js` | 단일 목표 또는 단계별 arrival-rate 용량 테스트 |
| `k6-verify-test.js` | 로컬 소규모 확인용 |
| `k6-sync-test.js` | 제거된 v1 동기 API 비교용 보관 스크립트, 실행 금지 |
| `k6-bulk-test.js` | 제거된 v1 bulk API 비교용 보관 스크립트, 실행 금지 |

실험 조건과 결과는 기존 `run-test.sh` 실행 절차에 맞춰 보관한다. k6 요약 JSON이 필요하면 실행마다 고유한 ID로 `evidence/` 아래 디렉터리를 만들고, k6 기본 제공 환경변수를 사용한다.

```bash
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"
mkdir -p "evidence/$RUN_ID"
K6_SUMMARY_EXPORT="evidence/$RUN_ID/k6-summary.json" \
K6_SUMMARY_TREND_STATS='avg,min,med,max,p(90),p(95),p(99)' \
bash ./run-test.sh local integrity
```

`evidence/`는 Git에서 제외된다. 이 JSON은 실행 전체의 k6 집계이며 안정 구간만의 지연이나 Redis Queue·Kafka lag·DB 정합성을 대신하지 않는다. 실환경 실행에서 함께 모을 증거와 판정 규칙은 8장에 있다. 실환경 테스트는 아래 사전 점검을 거쳐 별도로 시작한다.

## 3. 공통 사전 점검

### 3.1 환경 기동

로컬 WSL의 `ops` 디렉터리에서 실행한다.

```bash
cd /mnt/c/Users/user/Desktop/1milion-campaign-orchestration-system/ops
make check-tools
make env-up
```

`env-up` 성공 조건:

- AWS 계정과 리전이 의도한 값과 일치
- ASG `InService`가 최소 용량에 도달
- RDS, ElastiCache, Kafka, monitoring 서버가 기동
- Redis exporter가 현재 ElastiCache endpoint를 바라봄

### 3.2 ASG와 ALB 확인

```bash
aws autoscaling describe-auto-scaling-groups \
  --region ap-northeast-2 \
  --auto-scaling-group-names batch-kafka-app-asg \
  --query 'AutoScalingGroups[0].{Min:MinSize,Max:MaxSize,Desired:DesiredCapacity,Instances:Instances[*].{Id:InstanceId,LT:LaunchTemplate.Version,State:LifecycleState,Health:HealthStatus}}' \
  --output table

TG_ARN=$(aws elbv2 describe-target-groups \
  --region ap-northeast-2 \
  --names tg-api-8080 \
  --query 'TargetGroups[0].TargetGroupArn' \
  --output text)

aws elbv2 describe-target-health \
  --region ap-northeast-2 \
  --target-group-arn "$TG_ARN" \
  --query 'TargetHealthDescriptions[*].{Id:Target.Id,State:TargetHealth.State,Reason:TargetHealth.Reason}' \
  --output table
```

모든 테스트 대상 인스턴스가 `InService`, ALB target이 `healthy`가 된 뒤 시작한다.

### 3.3 API와 비동기 경로 Smoke test

```bash
BASE_URL=$(bash ../stress-test/resolve-base-url.sh)

curl -fsS "$BASE_URL/actuator/health"

SMOKE_RESPONSE=$(curl -fsS -X POST "$BASE_URL/api/admin/campaigns" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"smoke-$(date +%s)\",\"totalStock\":100}")

echo "$SMOKE_RESPONSE"
```

첫 참여는 `202`, 동일 사용자의 재요청은 `409`, 잠시 뒤 status의 `successCount=1`과 `currentStock=99`를 확인한다.

### 3.4 모니터링 기준선

부하 시작 전 다음 값이 정상인지 확인한다.

- Redis Queue: `0`
- Kafka consumer group lag: `0`
- Hikari pending: `0`
- ALB healthy target 수: 의도한 ASG desired와 동일

## 4. 150만 정합성 테스트

### 4.1 캠페인 생성

```bash
# 3.3에서 조회한 BASE_URL을 같은 셸에서 재사용한다.
test -n "$BASE_URL"

curl -fsS -X POST "$BASE_URL/api/admin/campaigns" \
  -H 'Content-Type: application/json' \
  -d '{"name":"load-test-1.5m","totalStock":1500000}'
```

응답의 캠페인 ID를 기록한다.

### 4.2 부하 발생기 접속

부하는 monitoring/terraform-mcp EC2에서 실행한다. 로컬 PC의 네트워크와 CPU가 측정값에 섞이지 않게 하기 위해서다.

```bash
aws ssm start-session \
  --region ap-northeast-2 \
  --target <terraform-mcp-instance-id>

cd ~/1milion-campaign-orchestration-system/stress-test
```

SSM 세션은 로컬 셸의 `BASE_URL`을 자동으로 전달하지 않는다. `terraform-mcp` EC2 역할에
`terraform_mcp_alb_describe` IAM 정책을 적용했다면 원격 `run-test.sh prod`가 현재 ALB 주소를
직접 조회한다. 정책 적용 전에는 3.3에서 운영 PC로 조회한 URL을 원격 세션의 `BASE_URL`에 명시한다.
오래된 ALB 주소를 재사용하지 않는다.

### 4.3 실행

```bash
# ALB 조회 IAM 정책이 적용된 terraform-mcp에서는 BASE_URL을 지정하지 않는다.
unset BASE_URL CONFIRM_BASE_URL_OVERRIDE
CAMPAIGN_ID=<id> \
TOTAL_REQUESTS=1500000 \
MAX_VUS=3000 \
DURATION=3600 \
bash ./run-test.sh prod integrity
```

ALB 조회 IAM 정책을 아직 적용하지 못했다면, 위 실행 전에 운영 PC에서 확인한 새 주소를
`BASE_URL`로 입력하고 `CONFIRM_BASE_URL_OVERRIDE=true`를 설정한다.

`DURATION`은 도착률을 제어하지 않는다. `k6-load-test.js`에서는 `maxDuration=DURATION*2` 계산에만 사용된다. 실제 요청 속도는 VU 수와 응답시간에 의해 결정된다.

### 4.4 통과 조건

| 구간 | 통과 조건 |
| --- | --- |
| API | `participation_success=1,500,000`, `participation_5xx=0`, `participation_fail=0` |
| Redis | 재고 0, 실제 Queue LLEN 0 |
| Bridge/Kafka | publish 종료 후 consumer lag 0 |
| DB | `successCount=1,500,000`, `failCount=0` |
| 정합성 | campaign-user 중복 0, sequence 중복 0 |

```bash
if [[ -z "${BASE_URL:-}" ]]; then
  BASE_URL="$(bash ./resolve-base-url.sh)"
fi
curl -fsS "$BASE_URL/api/campaigns/<id>/status" | python3 -m json.tool
```

DB 직접 검증:

```sql
SELECT COUNT(*) AS success_count
FROM participation_history
WHERE campaign_id = <id> AND status = 'SUCCESS';

SELECT sequence, COUNT(*)
FROM participation_history
WHERE campaign_id = <id>
GROUP BY sequence
HAVING COUNT(*) > 1;

SELECT user_id, COUNT(*)
FROM participation_history
WHERE campaign_id = <id>
GROUP BY user_id
HAVING COUNT(*) > 1;
```

Redis 직접 검증:

```bash
redis-cli -c -h <elasticache-configuration-endpoint> \
  LLEN 'queue:campaign:{<id>}'
```

Grafana Gauge만으로 최종 Queue 0을 판정하지 않는다. 애플리케이션 버전이 Queue Gauge 종료 처리를 포함하지 않으면 마지막 non-zero 값이 남을 수 있다.

## 5. 지속 가능한 처리량 테스트

### 5.1 단계별 탐색

```bash
RPS_STAGES=1000,2000,3000,4000,5000 \
STEP_RAMP_SECONDS=15 \
STAGE_SECONDS=120 \
PRE_ALLOCATED_VUS=3000 \
MAX_VUS=12000 \
P95_MS=1000 \
MAX_FAIL_RATE=0.01 \
bash ./run-test.sh prod capacity
```

각 단계는 목표 RPS까지 램프업한 뒤 지정한 시간 동안 유지한다. 한계 구간이 보이면 범위를 좁혀 다시 실행한다.

```bash
TARGET_RPS=3500 \
STEADY_SECONDS=600 \
PRE_ALLOCATED_VUS=2500 \
MAX_VUS=8000 \
bash ./run-test.sh prod capacity
```

### 5.2 sustainable TPS 판정

다음 조건을 모두 만족하는 가장 높은 목표 RPS를 운영 가능 처리량으로 본다.

1. `dropped_iterations=0`
2. 참여 API의 `participation_5xx=0` (별도로 전체 HTTP `http_req_failed` 비율도 확인)
3. API p95 `< 1초` 또는 별도로 정한 SLO 충족
4. 정상 상태 CPU가 목표 상한 이내이며 scale-out 후 다시 안정화
5. Redis Queue 기울기가 steady 구간에서 계속 증가하지 않음
6. Kafka lag가 발산하지 않음
7. DB commit TPS가 장기적으로 accepted TPS를 따라감
8. Hikari pending과 DB transient failure가 지속적으로 증가하지 않음

Spike 테스트에서 API가 5,000 TPS를 기록했더라도 DB commit이 1,700 TPS이고 Queue가 계속 증가했다면 5,000 TPS를 지속 가능한 End-to-End 처리량이라고 부를 수 없다.

## 6. Grafana 지표 해석

| 구간 | 지표 | 의미 |
| --- | --- | --- |
| 앞단 | API TPS | Redis Lua 처리와 Queue 적재 후 `202`를 반환한 속도 |
| 중간 | Redis Queue size | 앞단 유입과 Bridge 배출 속도의 누적 차이 |
| 중간 | Bridge publish TPS | Redis Queue에서 Kafka로 발행 완료한 속도 |
| 후단 | Kafka poll TPS | Consumer가 Kafka에서 가져온 record 속도 |
| 후단 | DB committed TPS | Consumer DB 성공 경로로 처리된 이벤트 속도 |
| 후단 | DB commit batch size | Consumer listener 1회당 DB 성공 경로 이벤트 수의 전역 가중 평균 |

`consumer_db_committed_total`은 현재 물리 INSERT row 수가 아니라 DB 성공 처리 경로를 통과한 이벤트 수다. 재전달이나 `INSERT IGNORE` 중복이 있는 테스트에서는 최종 DB row count를 별도 조회한다.

`consumer.db.commit.batch.size`는 JDBC packet byte 크기가 아니다. `DistributionSummary`에 기록한 `committedCount`의 평균이다. `max.poll.records=100`은 최대치일 뿐 최소 batch 크기를 보장하지 않는다.

전역 평균 PromQL:

```promql
sum(rate(consumer_db_commit_batch_size_sum[1m]))
/
sum(rate(consumer_db_commit_batch_size_count[1m]))
```

## 7. ASG 비교 실험

2대, 3대, 4대를 비교할 때는 같은 arrival-rate 단계와 같은 캠페인 재고를 사용한다.

기록 항목:

- accepted TPS와 p95/p99
- 인스턴스별 CPU
- Bridge TPS와 DB commit TPS
- Queue 최대 적재량과 drain 완료 시간
- Kafka lag peak
- Hikari pending

앱 EC2를 추가하면 API와 Bridge의 CPU 여유는 늘어날 가능성이 높다. 하지만 Kafka 파티션은 10개이므로 Consumer 병렬성과 DB 처리량은 선형으로 증가하지 않는다.

## 8. 실행 기록과 판정

실환경 실행마다 조건과 증거를 운영 PC 저장소 루트의 `evidence/<RUN_ID>/` 하나에 모은다. 증거는 환경을 내리기 전에 모은다. `make env-down` 뒤에는 Redis 잔량, 최종 DB 건수, 앱 인스턴스 로그를 다시 확인할 수 없다.

```bash
# 운영 PC, 저장소 루트
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"
EVID="evidence/$RUN_ID"
mkdir -p "$EVID"
```

부하 발생기(terraform-mcp)의 `run-test.sh`에도 같은 `RUN_ID`를 써서 `K6_SUMMARY_EXPORT`를 지정한다(2장).

실행 전 부하 발생기의 저장소가 테스트할 커밋과 같은지 확인한다. 부하 발생기 저장소는 자동으로 갱신되지 않아서 구버전 k6 스크립트로 실행될 수 있다.

```bash
# 운영 PC, 저장소 루트
source ops/scripts/lib-ssm.sh
ssm_run "$(mcp_instance_id)" 'd=/home/ssm-user/1milion-campaign-orchestration-system
git -c safe.directory="$d" -C "$d" log --oneline -1
md5sum "$d/stress-test/k6-load-test.js" "$d/stress-test/k6-tps-test.js" "$d/stress-test/run-test.sh"'
md5 stress-test/k6-load-test.js stress-test/k6-tps-test.js stress-test/run-test.sh   # 로컬과 비교

# 다르면 fast-forward로 맞춘다 (파일 소유자를 바꾸지 않도록 ssm-user로 실행)
ssm_run "$(mcp_instance_id)" 'cd /home/ssm-user/1milion-campaign-orchestration-system && sudo -u ssm-user git pull --ff-only origin main'
```

### 8.1 실행 조건

`$EVID/manifest.md`에 아래 항목을 채운다. 두 실행을 비교할 때 이 파일끼리 대조한다.

```text
RUN_ID:
시작·종료 시각(KST):
모드: integrity | capacity        비교 대상 RUN_ID:
바꾼 변수(한 가지):
앱 이미지 태그(SSM /batch-kafka/prod/ECR_IMAGE), Launch Template 버전:
부하 발생기 위치, k6 스크립트 커밋(git rev-parse HEAD):
캠페인 ID, 재고, TOTAL_REQUESTS 또는 RPS 단계·유지 시간, VU:
ASG min/desired/max, InService 수, 인스턴스 타입:
Kafka 파티션 수, RDS·Redis 사양, 로그 수준:
실행 중 변경(scale-out 시각, 배포, 설정 변경. 없으면 "없음"):
```

### 8.2 수집할 증거

| 파일 | 내용 | 수집 방법 | integrity | capacity |
| --- | --- | --- | --- | --- |
| `manifest.md` | 실행 조건 | 8.1 | 필수 | 필수 |
| `k6-summary.json`, `k6-run.log` | k6 집계와 콘솔 출력 | 부하 발생기에서 `K6_SUMMARY_EXPORT`, `run-test.sh ... 2>&1 \| tee` 후 운영 PC로 옮김 | 필수 | 필수 |
| `status.json` | 상태 API의 `successCount`·`failCount`·재고 | 4.4의 status `curl` 출력 저장 | 필수 | 필수 |
| `redis-watch.log` | `LLEN`, `SISMEMBER active:campaigns`, 재고의 1초 단위 기록 | 부하 전 `./ops/scripts/redis-watch-start.sh <id>`, drain 후 `./ops/scripts/redis-watch-fetch.sh --stop "$EVID"` | 필수 | 필수 |
| `kafka-lag.json` | consumer group lag | Prometheus `sum(kafka_consumergroup_lag{consumergroup="campaign-participation-group"})` 조회 결과 | 필수 | 필수 |
| `db-counts.txt` | SUCCESS 건수, sequence·user 중복 | 4.4의 DB 직접 검증 SQL 결과 저장 | 필수 | 필수 |
| `consistency.json` | 정합성 dry-run 결과(`anomalyCount`) | `POST /api/admin/consistency-recovery`(`dryRun=true`, `autoFix=false`, `campaignId`) 후 `GET .../executions/<id>` 응답 저장 | 필수 | 필수 |
| `prometheus/*.json` | 안정 구간의 Queue size, lag, DB committed TPS, CPU 시계열 | Prometheus `query_range` 결과. 쿼리·구간·step을 파일에 같이 남김 | 선택 | 필수 |
| `app-*.log`, `app-*.meta` | 인스턴스별 앱 로그, 컨테이너 이미지·시작 시각 | `./ops/scripts/collect-app-logs.sh "$EVID"` | 필수 | 필수 |
| `verdict.md` | 판정, 근거 파일, 빠진 증거 | 8.3 | 필수 | 필수 |

- Redis는 Grafana Gauge가 아니라 `redis-watch.log`의 원본 `LLEN`으로 판정한다(4.4).
- DB 건수는 `consumer_db_committed_total`이 아니라 `participation_history` 직접 조회로 판정한다(6장).
- 수치를 남길 때 원본 쿼리, 집계 구간, 단위, 인스턴스별·전역 집계 방식을 같이 적어 다시 계산할 수 있게 한다.
- 비밀값, 접속 정보, 개인 식별자, 대량 원본 이벤트는 저장하지 않는다.

수집 명령 (운영 PC, 저장소 루트, `source ops/scripts/lib-ssm.sh` 이후):

```bash
MCP=$(mcp_instance_id)
R=/home/ssm-user/1milion-campaign-orchestration-system/stress-test/evidence/$RUN_ID

# k6 결과: 부하 발생기에서 운영 PC로 가져온다.
ssm_fetch "$MCP" "cat $R/k6-summary.json" "$EVID/k6-summary.json"
ssm_fetch "$MCP" "cat $R/k6-run.log" "$EVID/k6-run.log"

# Kafka lag: Prometheus는 terraform-mcp의 localhost:9090에 있다.
LAG='sum(kafka_consumergroup_lag{consumergroup="campaign-participation-group"})'
ssm_run "$MCP" "curl -s -G localhost:9090/api/v1/query --data-urlencode 'query=$LAG'" > "$EVID/kafka-lag.json"
# 구간 조회는 query_range에 start·end(UTC epoch)·step을 넣고, 같은 값을 prometheus/README.txt에 적는다.
```

DB 직접 조회는 전용 스크립트가 없다. `ops/scripts/close-stale-campaigns.sh`와 같은 방식으로 앱 인스턴스에서 `mysql:8.4` 컨테이너를 띄워 4.4의 SELECT만 실행하고, 실행 시각·인스턴스·SQL을 출력과 함께 `db-counts.txt`에 남긴다. 이 스크립트의 `CONFIRM=yes` 경로는 데이터를 바꾸므로 재사용하지 않는다.

### 8.3 판정

`verdict.md`에는 PASS, FAIL, INCONCLUSIVE 중 하나와 근거 파일을 적는다.

| 판정 | 조건 |
| --- | --- |
| FAIL | 수집한 증거 중 하나라도 4.4(integrity) 또는 5.2(capacity) 기준을 만족하지 않음 |
| INCONCLUSIVE | 실패는 없지만 아래 중 하나에 해당 |
| PASS | 8.2의 필수 증거가 모두 있고, 모두 기준을 만족 |

INCONCLUSIVE 조건:

- 필수 증거가 하나라도 없음. 특히 Redis·Kafka·DB·정합성 같은 후단 증거가 없음
- 고정 용량 비교 실행인데 scale-out이 일어났거나, 실행 중 배포·설정이 바뀜
- `dropped_iterations > 0`이거나 k6가 목표 요청 수·RPS를 내지 못함
- 판정에 필요한 지표가 수집 구간에서 비어 있음

규칙:

- k6 결과만으로 PASS를 쓰지 않는다. `202`는 Redis Queue 적재까지만 보여 준다.
- INCONCLUSIVE를 보고서에서 PASS나 FAIL로 바꿔 쓰지 않는다. 결론이 필요하면 조건을 맞춰 다시 실행한다.
- `k6-summary.json`의 threshold 값은 `true`가 기준 위반이다.
- `consistency.json`의 `anomalyCount`는 4.4 통과 조건에 포함되지 않는다. 이상이 있으면 유형(`anomalyType`)과 심각도(`severity`)를 `verdict.md`에 따로 적는다.

### 8.4 두 실행 비교

1. 두 `manifest.md`를 대조한다. 의도한 변수 하나 외에 다른 조건이 다르면 비교하지 않는다.
2. 두 실행 모두 비교에 쓸 지표의 증거가 있어야 한다. 어느 한쪽이 INCONCLUSIVE이면 그 사유가 비교 지표와 무관할 때만 비교한다.
3. 같은 조건을 두 번 이상 반복해 변동폭을 확인한다. 두 실행의 차이가 변동폭 안이면 "차이 없음"으로 기록한다.
4. 같은 길이의 안정 구간끼리 비교한다. 서로 다른 시점의 peak를 합쳐 하나의 처리량으로 쓰지 않는다.
5. 결과는 아래 표로 남긴다. 모든 값에 출처 파일을 적는다.

| 지표 | 실행 A | 실행 B | 차이 | 출처 |
| --- | --- | --- | --- | --- |
| 안정 구간 202 p95 / p99 | | | | `k6-summary.json` 또는 `prometheus/` |
| `dropped_iterations`, `participation_5xx`, `http_req_failed` | | | | `k6-summary.json` |
| Redis Queue 최대값, 안정 구간 기울기, drain 완료 시각 | | | | `redis-watch.log`, `prometheus/` |
| Kafka lag 최대값 | | | | `kafka-lag.json`, `prometheus/` |
| DB committed TPS(안정 구간) | | | | `prometheus/` |
| 앱 CPU(인스턴스별) | | | | `prometheus/` |
| 최종 DB 건수, 중복, `anomalyCount` | | | | `db-counts.txt`, `consistency.json` |

## 9. 테스트 종료

8장의 증거 수집과 판정을 마친 뒤 환경을 내린다.

```bash
cd /mnt/c/Users/user/Desktop/1milion-campaign-orchestration-system/ops
make env-down
```

현재 운영 절차에서는 개별 EC2/RDS를 콘솔에서 수동 종료하거나 Terraform target destroy 명령을 조합하지 않는다. `ops`의 Ansible/Makefile 흐름을 사용한다.
