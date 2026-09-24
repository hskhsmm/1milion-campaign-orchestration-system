# 2026-09-20 AWS 환경 복구와 150만 건 재검증

## 1. 목적

몇 개월 동안 중지했던 AWS 부하 테스트 환경을 다시 기동하면서 발생한 배포 실패를 해결하고, ASG 앱 인스턴스 3대 조건에서 Redis-first v3의 150만 건 처리와 정합성을 재검증했다.

이번 기록은 다음을 구분한다.

- API가 Redis Queue까지 접수하고 `202 Accepted`를 반환하는 앞단 처리량
- Redis Queue에서 Kafka로 발행하는 Bridge 처리량
- Kafka Consumer가 DB 성공 경로로 처리하는 후단 처리량
- 정확한 요청 수 기반 Spike 테스트와 지속 가능한 처리량 테스트의 차이

## 2. 환경 복구 중 발생한 문제

### 2.1 로컬 AWS CLI 경로 문제

WSL에서 `/usr/bin/aws`의 기존 AWS CLI를 사용했을 때 Ansible의 SSM `send-command`가 `badly formed help string`으로 실패했다.

AWS CLI를 `/usr/local/bin/aws`의 새 버전으로 설치하고 PATH 우선순위를 갱신한 뒤 해결했다.

```bash
export PATH="/usr/local/bin:$PATH"
hash -r
type -a aws
aws --version
```

운영 자동화는 실행 전에 `type -a aws`, `aws --version`, `aws sts get-caller-identity`를 확인해야 한다.

### 2.2 신규 ASG 인스턴스 반복 종료

Launch Template v3으로 생성된 인스턴스가 `Pending:Wait` 이후 CodeDeploy에 실패하고 종료됐다. ASG는 desired capacity를 맞추기 위해 다시 인스턴스를 만들었지만 같은 실패가 반복됐다.

원인은 두 bootstrap 경로의 경쟁이었다.

```text
EC2 user-data
└─ cloud-init이 dnf/pip로 Ansible 설치

CodeDeploy BeforeInstall
└─ run-ansible-deploy.sh가 동시에 dnf/pip로 Ansible 확인·설치
```

CodeDeploy 로그에는 다른 dnf 프로세스 대기와 DNF cache RPM 누락이 나타났다. CodeDeploy 실패가 ASG lifecycle hook의 ABANDON으로 이어져 신규 인스턴스가 종료됐다.

### 2.3 적용한 복구

| 커밋 | 내용 |
| --- | --- |
| `9f20bba` | AL2023 Python 3.9와 호환되는 Ansible 8.7.0 고정 |
| `5422361` | RPM Ansible과 pip Ansible 충돌 제거 |
| `73db095` | CodeDeploy가 Ansible 작업 전 cloud-init 완료를 기다리도록 수정 |

핵심 수정은 `run-ansible-deploy.sh`에서 `/var/lib/cloud/instance/boot-finished` 또는 `cloud-init status --wait`를 확인하는 것이다.

## 3. 복구 검증

1. Terraform으로 Launch Template v3 생성
2. GitHub Actions와 CodeDeploy 성공 확인
3. LT v3 인스턴스가 `Pending:Wait`에서 `InService`로 전환
4. ASG 앱 인스턴스 3대 모두 Healthy
5. ALB target 3대 모두 healthy
6. `/actuator/health` 응답 `UP`

Smoke campaign 60 검증:

- 첫 참여: `202 Accepted`
- 동일 사용자 재요청: `409 PARTICIPATION_001`
- 최종 상태: `successCount=1`, `failCount=0`, `currentStock=99`
- 테스트 전 Redis Queue와 Kafka lag: `0`

## 4. 150만 건 테스트 조건

| 항목 | 값 |
| --- | --- |
| campaign ID | `61` |
| campaign stock | `1,500,000` |
| 앱 서버 | `t3.small` 3대, ASG InService/Healthy |
| Kafka topic | 10 partitions |
| k6 executor | `shared-iterations` |
| VU | `3,000` |
| 총 요청 | `1,500,000` |
| 부하 발생기 | terraform-mcp EC2 |

실행 명령:

```bash
CAMPAIGN_ID=61 \
TOTAL_REQUESTS=1500000 \
MAX_VUS=3000 \
DURATION=3600 \
bash ./run-test.sh prod integrity
```

`DURATION=3600`은 1시간 동안 일정한 요청률을 유지한다는 뜻이 아니다. 현재 shared-iterations 스크립트에서는 `maxDuration` 계산에 사용되며, 150만 iteration이 끝나면 테스트도 끝난다.

## 5. 구간별 TPS 결과

Prometheus 1분 rate를 기준으로 테스트 구간의 peak와 대표 live sample을 구분했다.

| 구간 | 지표 | 동시 유입 중 대표값 | 테스트 구간 peak |
| --- | --- | ---: | ---: |
| 앞단 | API accepted TPS | 약 `5,000~5,336/s` | `5,336/s` |
| 중간 | Bridge publish TPS | 약 `1,660/s` | `4,546/s` |
| 후단 | Consumer DB success-path TPS | 약 `1,680~1,725/s` | `4,518/s` |

추가 관측값:

| 지표 | 결과 |
| --- | ---: |
| API p95 peak | `3.38s` |
| 안정 구간에서 관측한 API p95 | 약 `230~671ms` |
| Redis Queue sampled peak | `1,065,735` |
| Kafka consumer group lag peak | `273` |
| Hikari pending peak | `0` |
| 앱 process CPU peak | `98.24%` |
| 5xx | `0` |
| DB transient failure | `0` |

`consumer_db_committed_total`은 현재 구현상 DB 성공 처리 경로까지 도달한 이벤트 수다. 정상·중복 없는 이번 테스트에서는 최종 DB row count와 일치했지만, 재전달이나 `INSERT IGNORE` 중복이 있는 실험에서는 물리적으로 새로 INSERT된 row 수를 과대 계상할 수 있다.

## 6. 자원 경쟁에서 확인한 동작

API 요청이 계속 들어올 때 앱 CPU는 약 83~93%였고, Bridge와 DB success-path TPS는 약 1,700/s 수준이었다.

API 유입이 끝난 뒤에는 다음 변화가 나타났다.

| 지표 | 유입 중 | 유입 종료 후 |
| --- | ---: | ---: |
| API TPS | 약 `5,300/s` | `0` |
| CPU | `83~93%` | `29~37%` |
| Bridge TPS | 약 `1,660/s` | 약 `4,100/s` |
| DB success-path TPS | 약 `1,680/s` | 약 `4,100/s` |

따라서 후단의 절대 한계가 1,700 TPS였던 것은 아니다. API, Bridge, Consumer가 같은 앱 인스턴스의 CPU를 경쟁했고, 앞단 유입이 끝나자 후단이 가속됐다.

앱 EC2를 추가하면 동시 유입 구간의 API와 Bridge 여유가 늘어날 가능성이 높다. 다만 Kafka 파티션은 10개이며 DB와 Redis도 공유 자원이므로 인스턴스 수에 선형 비례한다고 단정할 수 없다.

## 7. 최종 정합성 결과

캠페인 status 응답:

```text
totalParticipation = 1,500,000
successCount       = 1,500,000
failCount          = 0
totalStock         = 1,500,000
currentStock       = 0
stockUsageRate     = 100.00%
```

Kafka lag은 최종 `0`, Bridge와 Consumer 처리율도 최종적으로 `0`에 수렴했다.

결론:

```text
Redis Lua accepted 1,500,000
→ Redis Queue에서 전량 pop
→ Kafka lag 0
→ DB success row 1,500,000
→ fail 0, remaining stock 0
```

이번 테스트는 150만 건의 정확한 총량과 최종 정합성 검증에는 성공했다.

## 8. Redis Queue Gauge 오류

처리가 끝났지만 Grafana의 Queue가 `34,598`에서 멈춘 것처럼 보였다. 원본 Prometheus series는 앱 인스턴스마다 서로 다른 값을 보였다.

| 앱 instance | 마지막 Gauge 값 |
| --- | ---: |
| `172.31.100.245:8080` | `28,830` |
| `172.31.101.7:8080` | `10,424` |
| `172.31.101.154:8080` | `34,598` |

세 인스턴스가 공유 Redis의 같은 List를 읽으므로 실제 LLEN이 서로 다를 수 없다. 각 JVM의 local Gauge가 마지막 값을 보관한 것이다.

원인:

1. `QueueMetricsScheduler`는 `active:campaigns`에 있는 ID만 LLEN으로 갱신한다.
2. Bridge는 Queue를 모두 비운 뒤 캠페인을 active Set에서 제거한다.
3. Scheduler는 제거된 캠페인을 다시 조회하지 않는다.
4. 각 JVM Gauge에 서로 다른 마지막 non-zero 값이 남는다.

수정:

- 현재 active ID를 수집
- 이미 등록됐지만 active Set에서 사라진 캠페인의 local Gauge를 `0`으로 갱신
- active Set이 비어도 기존 Gauge를 `0`으로 수렴
- 다중 캠페인과 전체 종료 케이스 단위 테스트 추가

## 9. DB commit batch size 해석

패널의 값은 JDBC byte batch 크기가 아니다.

```java
DistributionSummary("consumer.db.commit.batch.size")
    .record(committedCount);
```

즉 Kafka listener 호출 1회에서 DB 성공 경로로 처리한 이벤트 수의 평균이다.

기존 Grafana 쿼리는 인스턴스별 평균을 같은 범례로 겹쳐 표시했다.

```promql
rate(sum[1m]) / rate(count[1m])
```

전역 가중 평균으로 수정했다.

```promql
sum(rate(consumer_db_commit_batch_size_sum[1m]))
/
sum(rate(consumer_db_commit_batch_size_count[1m]))
```

이번 테스트에서 전역 1분 평균 batch size의 peak도 약 `7.25`였다. `max.poll.records=100`은 한 번에 가져올 수 있는 최대치일 뿐 최소 batch 크기를 보장하지 않는다. 현재 기본 fetch 정책에서는 메시지가 도착하는 즉시 작은 batch로 자주 poll된 것으로 해석한다.

`fetch.min.bytes`와 `fetch.max.wait.ms`는 latency와 batch 효율의 trade-off가 있으므로 바로 운영값을 바꾸지 않고 arrival-rate A/B 테스트로 결정한다.

## 10. Spike와 sustainable TPS 구분

이번 `shared-iterations` 테스트는 다음을 증명했다.

- 정확히 150만 요청 수행
- 앞단 5,336 TPS peak
- Queue가 burst 차이를 흡수
- 최종 DB 150만 건, 유실 0

하지만 지속 가능한 End-to-End TPS는 아직 이 테스트만으로 확정할 수 없다. API가 약 5,300/s로 유입되는 동안 DB success-path는 약 1,700/s였고 Queue가 증가했기 때문이다.

지속 가능한 처리량은 `ramping-arrival-rate`로 다음 조건을 동시에 확인해야 한다.

1. `dropped_iterations=0`
2. API p95 SLO 충족
3. 5xx 허용치 이내
4. Redis Queue가 steady 구간에서 발산하지 않음
5. Kafka lag가 발산하지 않음
6. DB success-path TPS가 accepted TPS를 장기적으로 따라감

현행 실행 예시는 `stress-test/TEST_GUIDE.md`에 정리한다.

## 11. 추가 개선 과제

| 우선순위 | 상태 | 과제 | 이유 |
| --- | --- | --- | --- |
| P0 | 완료 | Queue Gauge 종료값 0 처리 | 실제 Queue가 비어도 Grafana에 잔량이 남는 오류 제거 |
| P0 | 완료 | Grafana 다중 인스턴스 평균 쿼리 수정 | 같은 범례의 여러 선과 잘못된 평균 제거 |
| P0 | 완료 | DB transient failure 이벤트 단위 계수 | 실패 batch 수가 아닌 실패 이벤트 수를 기록 |
| P0 | 완료 | MCP 정합성 감시 API 갱신 | 제거된 동기 endpoint 대신 dry-run 정합성 Job 결과를 추적 |
| P1 | 예정 | arrival-rate 단계별 capacity test | sustainable TPS 확정 |
| P1 | 예정 | Consumer fetch A/B test | 평균 batch size가 한 자릿수인 원인과 개선폭 검증 |
| P1 | 예정 | 물리 INSERT row counter 분리 | 재전달·INSERT IGNORE 상황에서 DB commit metric 과대 계상 방지 |
| P1 | 예정 | Consumer concurrency 설정 분리 | 각 인스턴스가 파티션 수만큼 thread를 생성해 총 thread가 과다해질 수 있음 |
| P2 | 예정 | true API-to-DB latency 추가 | 현재 latency timer는 consumer poll 이후 DB 처리 시간만 측정 |
| P2 | 예정 | ASG/EC2 CPU 평균 패널 추가 | `process_cpu_usage`와 ASG scaling 기준의 차이 설명 |

## 12. 최종 요약

이번 장애는 오래 쉬어서 발생한 막연한 cold start 문제가 아니라, 신규 인스턴스에서 cloud-init과 CodeDeploy가 동시에 패키지 설치를 수행한 bootstrap 경쟁이었다. cloud-init 완료를 배포 선행 조건으로 만들고 Ansible 설치 버전과 경로를 통일해 해결했다.

복구 후 ASG 3대에서 150만 shared-iterations 테스트를 수행했고, API 1분 peak `5,336/s`, Bridge peak `4,546/s`, DB success-path peak `4,518/s`, 최종 DB `1,500,000`, fail `0`, 5xx `0`을 확인했다.

이번 결과의 정확한 표현은 다음과 같다.

> Redis-first API는 150만 건 Spike를 최대 5,336 TPS의 1분 rate로 접수했고, Redis Queue가 앞단과 후단의 속도 차이를 흡수했다. 유입 종료 후 Bridge와 Consumer가 약 4,100 TPS로 backlog를 배출해 최종 DB 150만 건, fail 0, Kafka lag 0을 달성했다. 지속 가능한 End-to-End TPS는 별도의 arrival-rate 테스트로 확정한다.
