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
| `k6-load-test.js` | 정확한 요청 수 기반 Spike/정합성 테스트 |
| `k6-tps-test.js` | 단일 목표 또는 단계별 arrival-rate 용량 테스트 |
| `k6-verify-test.js` | 로컬 소규모 확인용 |
| `k6-sync-test.js` | 제거된 v1 동기 API 비교용 보관 스크립트, 실행 금지 |
| `k6-bulk-test.js` | 제거된 v1 bulk API 비교용 보관 스크립트, 실행 금지 |

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
BASE_URL=http://alb-batch-kafka-api-1351817547.ap-northeast-2.elb.amazonaws.com

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
BASE_URL=http://alb-batch-kafka-api-1351817547.ap-northeast-2.elb.amazonaws.com

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

### 4.3 실행

```bash
CAMPAIGN_ID=<id> \
TOTAL_REQUESTS=1500000 \
MAX_VUS=3000 \
DURATION=3600 \
bash ./run-test.sh prod integrity
```

`DURATION`은 도착률을 제어하지 않는다. `k6-load-test.js`에서는 `maxDuration=DURATION*2` 계산에만 사용된다. 실제 요청 속도는 VU 수와 응답시간에 의해 결정된다.

### 4.4 통과 조건

| 구간 | 통과 조건 |
| --- | --- |
| API | 202 성공 1,500,000건, 5xx 0건 |
| Redis | 재고 0, 실제 Queue LLEN 0 |
| Bridge/Kafka | publish 종료 후 consumer lag 0 |
| DB | `successCount=1,500,000`, `failCount=0` |
| 정합성 | campaign-user 중복 0, sequence 중복 0 |

```bash
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
2. 5xx 비율 `< 1%`
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

## 8. 테스트 종료

최종 Queue와 DB 정합성을 확인한 뒤 환경을 내린다.

```bash
cd /mnt/c/Users/user/Desktop/1milion-campaign-orchestration-system/ops
make env-down
```

현재 운영 절차에서는 개별 EC2/RDS를 콘솔에서 수동 종료하거나 Terraform target destroy 명령을 조합하지 않는다. `ops`의 Ansible/Makefile 흐름을 사용한다.
