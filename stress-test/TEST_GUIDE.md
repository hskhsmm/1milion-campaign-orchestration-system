# 100만 트래픽 달성을 위한 단계별 테스트 가이드

> **설계 철학**: 공정성·정합성 우선, 처리량은 안정적이면 충분
> **최종 목표**: 100만 Spike에서 SUCCESS ≤ 1,000,000 / sequence 중복 0건 / PENDING 0건
> **참고 문서**: `TEST_ROADMAP.md`, `DESIGN_IMPROVEMENTS.md`, `A파트_장애시나리오_설계_v4.pdf`

---

## 스크립트 현황

| 파일 | 용도 | v2 사용 여부 |
|------|------|-------------|
| `k6-load-test.js` | **메인 부하 테스트** — 정확한 요청 수, 선착순 참여 | ✅ 모든 Phase |
| `run-test.sh` | k6-load-test.js 실행 래퍼 (환경 분기, 파라미터 관리) | ✅ 사용 권장 |
| `k6-verify-test.js` | 소규모 정합성 빠른 확인 (VU 100명, 재고 50개) | ⚠️ 로컬 전용 (v1 응답코드 200 기준) |
| `k6-bulk-test.js` | v1 bulk API 테스트 (`/participate-bulk`) | ❌ v2 미사용 |
| `k6-sync-test.js` | v1 동기 API 테스트 (`/participation-sync`) | ❌ v2 미사용 |

**→ AWS 테스트는 `run-test.sh` 또는 `k6-load-test.js` 직접 사용**

---

## 사전 준비 (매 Phase 공통)

### 1. AWS 리소스 기동

```bash
# EC2 콘솔에서 start
# batch-kafka-app (t3.small)
# kafka-1 (t3.small)
# terraform-mcp (t3.small)
# batch-kafka-db (RDS MySQL — 콘솔 start)
# ElastiCache — terraform apply로 기동 (비용 절감용으로 평소 destroy 상태)
```

### 2. 모니터링 기동 (terraform-mcp)

```bash
# SSM으로 terraform-mcp 접속
aws ssm start-session --target <terraform-mcp-instance-id>

# Prometheus + Grafana + kafka-exporter 컨테이너 확인
docker ps
# 중지 상태면
docker start prometheus grafana kafka-exporter
```

### 3. 앱 배포 확인

```bash
# ALB health check
curl http://alb-batch-kafka-api-xxx.ap-northeast-2.elb.amazonaws.com/actuator/health
# {"status":"UP"} 확인
```

### 4. Prometheus targets 확인

```
http://<terraform-mcp-ip>:9090/targets
→ spring-boot, kafka-exporter, redis-exporter 3개 UP
```

### 5. 캠페인 생성

```bash
# run-test.sh와 같은 디렉토리에서 실행
BASE_URL=http://alb-batch-kafka-api-xxx.ap-northeast-2.elb.amazonaws.com

curl -X POST $BASE_URL/api/admin/campaigns \
  -H "Content-Type: application/json" \
  -d '{"name":"phase1","totalStock":10000,"startAt":"2026-01-01T00:00:00","endAt":"2026-12-31T23:59:59"}'

# 응답에서 id 확인 → CAMPAIGN_ID로 사용
```

### 6. run-test.sh ALB URL 설정

`run-test.sh` 열어서 실제 ALB URL로 교체:
```bash
BASE_URL="http://alb-batch-kafka-api-xxx.ap-northeast-2.elb.amazonaws.com"
# ↓
BASE_URL="http://실제ALB주소"
```

---

## 정합성 검증 스크립트 생성

> 테스트마다 아래 스크립트를 SSM으로 batch-kafka-app에서 실행

```bash
# stress-test/verify.sh 로컬에 생성 후 참고용으로 사용
# 실제 실행은 SSM으로 batch-kafka-app EC2 접속 후 MySQL CLI 사용

# SSM 접속
aws ssm start-session --target <batch-kafka-app-instance-id>

# Docker 컨테이너 내 MySQL 접속
docker exec -it <db-container-or-직접연결> mysql -h <RDS endpoint> -u <user> -p

# 검증 쿼리
SELECT COUNT(*) FROM participation_history WHERE campaign_id=<id> AND status='SUCCESS';
SELECT sequence, COUNT(*) FROM participation_history WHERE campaign_id=<id> GROUP BY sequence HAVING COUNT(*)>1;
SELECT COUNT(*) FROM participation_history WHERE campaign_id=<id> AND status='PENDING';
```

**정상 기준:**
- SUCCESS count = totalStock (10,000)
- sequence 중복 = 0건
- PENDING = 0건

---

## Phase 현황

| Phase | 핵심 변경 | 상태 | 비고 |
|-------|-----------|------|------|
| 0 | 기준선 측정 | ✅ 완료 | TPS 246/s, p95 6.34s |
| 1 | 코드 개선 (A파트 Lua 통합 + B파트 동적 batchSize) | 🔄 A파트 팀원 작업 중 | merge 후 즉시 테스트 |
| 2 | gp3 Soak 테스트 | 인프라 완료, 테스트 대기 | Phase 1 직후 연속 진행 |
| 3 | HikariCP 튜닝 | 대기 | yml 수정 + 재배포 |
| 4 | Kafka 3브로커 + 10파티션 | 대기 | EC2 2대 추가 필요 |
| 5 | Redis Cluster 3샤드 + AOF + Intent Key | 대기 | elasticache.tf 수정 필요 |
| 6 | Spring Batch 안전망 | 대기 | 코드 작업 필요 |
| 7 | Aurora *(비용 판단)* | 선택 | |
| 8 | 백만 Spike 최종 검증 | 대기 | 전체 완료 후 |

---

## Phase 1 — 코드 개선 테스트

### 선행 조건
- A파트(totalStock 캐싱, 캠페인 자동 종료, INCR 제거) + B파트(동적 batchSize) develop → main merge
- CI/CD 배포 완료

### 테스트 실행 (Phase 0과 동일 조건 — before/after 비교)

```bash
CAMPAIGN_ID=<id> TOTAL_REQUESTS=15000 MAX_VUS=1000 DURATION=60 \
  ./stress-test/run-test.sh prod
```

### 통과 기준

| 지표 | Phase 0 | Phase 1 목표 |
|------|---------|-------------|
| TPS | 246/s | 300/s 이상 |
| avg latency | 3.94s | 2~3s |
| p95 | 6.34s | 4s 미만 |
| 재고 초과 발급 | 0건 | **0건 필수** |
| hikaricp_connections_pending | - | Phase 0 대비 감소 |

### Grafana 확인 항목
- `hikaricp_connections_pending` 감소 확인 (DB 조회 제거 효과)
- `redis_queue_size` 0 수렴 확인
- `bridge_drain_duration` 안정적 유지

### 장애 시나리오

```bash
# ① Redis 다운 → 503 확인
# k6 실행 중 kafka-1 SSM에서
docker stop redis
# → 503 반환, 재고 차감 없음 확인
# → docker start redis 후 정상 복구 확인

# ① 중복 요청 → 429 확인
curl -X POST $BASE_URL/api/campaigns/<id>/participation \
  -H "Content-Type: application/json" -d '{"userId":1}'
# 10초 내 동일 요청 재전송 → 429 응답 확인

# ② remaining < 0, INCR 없음 확인 (A파트 적용 후)
CAMPAIGN_ID=<id> TOTAL_REQUESTS=20000 MAX_VUS=1000 DURATION=60 \
  ./stress-test/run-test.sh prod
# → DB에서 SUCCESS = 10,000건, Redis stock 음수 유지 확인
```

---

## Phase 2 — gp3 Soak 테스트

### 선행 조건
- Phase 1 통과
- RDS gp3 전환 완료 (이미 완료)

### 테스트 실행 (장시간 IOPS 안정성)

```bash
# 재고 50,000 캠페인 신규 생성
curl -X POST $BASE_URL/api/admin/campaigns \
  -H "Content-Type: application/json" \
  -d '{"name":"phase2-soak","totalStock":50000,"startAt":"2026-01-01T00:00:00","endAt":"2026-12-31T23:59:59"}'

CAMPAIGN_ID=<id> TOTAL_REQUESTS=50000 MAX_VUS=500 DURATION=1800 \
  ./stress-test/run-test.sh prod
```

### 통과 기준
- 30분 동안 latency 증가 없음 (gp2 대비 안정)
- AWS 콘솔 → RDS → Performance Insights → IOPS 그래프 일정하게 유지

### 장애 시나리오

```bash
# ③ INSERT 실패 → sequence 유지 재시도
# HikariCP connection-timeout을 임시로 3초로 낮춰 타임아웃 유도
# → 같은 sequence로 재시도, MAX_RETRY 소진 후 INCR + Slack 알림 수신 확인
```

---

## Phase 3 — HikariCP 튜닝

> 앱 서버 추가 없이 커넥션 풀 설정만 최적화.
> Phase 1 코드 개선 + Phase 3 HikariCP 튜닝으로 단일 앱 서버의 DB 처리량 극대화.

### 선행 조건
- Phase 2 통과
- `application-prod.yml` 수정 후 CI/CD 재배포:
  ```yaml
  spring:
    datasource:
      hikari:
        maximum-pool-size: 20
        connection-timeout: 3000
  ```

### 테스트 실행

```bash
CAMPAIGN_ID=<id> TOTAL_REQUESTS=20000 MAX_VUS=2000 DURATION=80 \
  ./stress-test/run-test.sh prod
```

### 통과 기준
- `hikaricp_connections_pending` 감소 또는 0 근접
- TPS 향상 또는 latency 감소 확인

### 장애 시나리오

```bash
# ③ INSERT 실패 → sequence 유지 재시도
# HikariCP connection-timeout 3초 설정 후 부하 → 타임아웃 유도
# → 같은 sequence로 재시도, MAX_RETRY 소진 후 Slack 알림 확인
```

---

## Phase 4 — Kafka 3브로커 + 10파티션

### 선행 조건
- Phase 3 통과
- Terraform 작업:
  ```bash
  # infra/ec2.tf — kafka-2, kafka-3 EC2 추가
  terraform apply
  ```
- kafka-2, kafka-3 SSM 접속 → Docker 설치 → Kafka 브로커 기동
- 토픽 재생성:
  ```bash
  kafka-topics.sh --bootstrap-server kafka-1:9092 \
    --delete --topic campaign-participation-topic
  kafka-topics.sh --bootstrap-server kafka-1:9092 \
    --create --topic campaign-participation-topic \
    --partitions 10 --replication-factor 3
  kafka-topics.sh --bootstrap-server kafka-1:9092 \
    --create --topic campaign-participation-topic.dlq \
    --partitions 3 --replication-factor 3
  ```
- `application-prod.yml` KAFKA_BROKER_2, KAFKA_BROKER_3 SSM 등록
- SSM에 `KAFKA_BROKER_2`, `KAFKA_BROKER_3` 등록 후 CI/CD 재배포
- 앱 재시작 → KafkaConfig 자동 감지 → concurrency=10 확인

### 파티션 테스트 순서 (단일 브로커에서 점진적으로 올리기)

> 3-broker 구성 전에 단일 브로커로 파티션 수 변화만 먼저 측정 가능

```bash
# 파티션 N개로 변경
kafka-topics.sh --alter --topic campaign-participation-topic --partitions N
# 앱 재시작 (concurrency 자동 감지)
# yml 프로필 적용: SPRING_PROFILES_ACTIVE=prod,p3 (p2/p3/p5/p10)
# k6 실행
CAMPAIGN_ID=<id> TOTAL_REQUESTS=30000 MAX_VUS=2000 DURATION=60 \
  ./stress-test/run-test.sh prod
```

| 파티션 | 프로필 | 예상 TPS |
|--------|--------|---------|
| 1 | prod | ~246/s (기준) |
| 2 | prod,p2 | ~450/s |
| 3 | prod,p3 | ~600/s |
| 5 | prod,p5 | ~800/s |
| 10 | prod,p10 | ~1,000/s (DB 병목 도달) |

### 통과 기준
- `consumer_pending_to_success_latency` 감소
- Kafka 브로커 1대 다운 시 메시지 유실 없음

### 장애 시나리오

```bash
# Kafka 브로커 1대 다운
# k6 실행 중 kafka-2 SSM 접속
docker stop kafka
# → 메시지 유실 없음, replication으로 자동 복구 확인
# → k6 에러율 < 1% 유지 확인
```

---

## Phase 5 — Redis Cluster 3샤드 + AOF + Intent Key

### 선행 조건
- Phase 4 통과
- Terraform 작업:
  ```bash
  # infra/elasticache.tf — Cluster 모드 3샤드, AOF 활성화
  terraform apply
  ```
- `RedisConfig.java` — RedisClusterConfiguration 적용
- Intent Key Lua 구현 (`DECR + SET intent:campaign:{id}:user:{userId} EX 30`)
- `application-prod.yml` Redis Cluster 엔드포인트 반영
- CI/CD 재배포

### 테스트 실행

```bash
CAMPAIGN_ID=<id> TOTAL_REQUESTS=30000 MAX_VUS=2000 DURATION=60 \
  ./stress-test/run-test.sh prod
```

### 장애 시나리오

```bash
# ① Redis 샤드 1개 다운 → HA 확인
# ElastiCache 콘솔에서 노드 1개 제거 (failover 유도)
# → k6 에러율 < 1% 유지 확인 (503 없이 처리 지속)

# Intent Key — 앱 크래시 후 재시도
# DECR 직후 SSM으로 앱 강제 종료
docker kill <app-container>
# → 재기동 후 동일 userId 재요청
# → double DECR 없음 (동일 sequence 유지) 확인

# AOF replay
# Redis 다운 → 재기동
# → stock 키 + intent key 복구 확인
```

---

## Phase 6 — Spring Batch 안전망

### 선행 조건
- Phase 5 통과
- 코드 작업:
  - `ItemReader`: 5분 초과 PENDING 조회
  - `ItemProcessor`: Redis Queue 재발행 가능 여부 판단
  - `ItemWriter`: 재발행 성공 → 유지, 실패 → FAIL UPDATE
  - 재고 복구 스케줄러: `total - SUCCESS - PENDING` 공식

### 장애 시나리오

```bash
# PENDING 5분 방치 → Spring Batch 재처리
# Bridge 컨테이너 강제 중단 5분
docker pause <app-container>  # Bridge 스케줄러 멈춤
sleep 300
docker unpause <app-container>
# → Spring Batch 감지 → 재발행 → SUCCESS 전환 확인

# Redis 강제 다운 → 재기동 → 재고 복구
docker stop redis
docker start redis
# → stock 키 없어짐 → 복구 스케줄러 1분 내 `total - SUCCESS - PENDING` 계산 후 복구
# → 이후 요청 정상 처리 확인
```

---

## Phase 7 — Aurora *(비용 판단 후 적용)*

### 선행 조건
- Phase 6 통과
- Aurora MySQL 클러스터 생성 (콘솔, 코드 변경 없음 — 엔드포인트만 교체)
- RDS Proxy 추가
- SSM `/batch-kafka/prod/SPRING_DATASOURCE_URL` Aurora 엔드포인트로 교체

### 테스트 실행 (극단 Spike)

```bash
# 재고 100,000 캠페인 생성
CAMPAIGN_ID=<id> TOTAL_REQUESTS=150000 MAX_VUS=5000 DURATION=80 \
  ./stress-test/run-test.sh prod
```

### 통과 기준
- INSERT TPS 1,000+ (MySQL db.t3.micro 대비 최대 5배)
- Performance Insights로 Aurora vs MySQL INSERT 속도 비교 기록

---

## Phase 8 — 백만 Spike 최종 검증

### 선행 조건
- Phase 6 통과 (Phase 7은 선택)
- 전체 인프라:
  - 앱 2대 (t3.small × 2)
  - Kafka 3브로커 + 10파티션
  - Redis Cluster 3샤드
  - Aurora 또는 MySQL gp3

### 테스트 실행

```bash
# 재고 1,000,000 캠페인 생성
curl -X POST $BASE_URL/api/admin/campaigns \
  -H "Content-Type: application/json" \
  -d '{"name":"final-1M","totalStock":1000000,"startAt":"2026-01-01T00:00:00","endAt":"2026-12-31T23:59:59"}'

# 백만 Spike
CAMPAIGN_ID=<id> TOTAL_REQUESTS=1200000 MAX_VUS=5000 DURATION=240 \
  ./stress-test/run-test.sh prod
```

### 최종 정합성 확인

```sql
-- SSM으로 앱 서버 접속 후 MySQL 실행
SELECT COUNT(*) FROM participation_history
  WHERE campaign_id=<id> AND status='SUCCESS';
-- 결과: ≤ 1,000,000

SELECT sequence, COUNT(*) FROM participation_history
  WHERE campaign_id=<id>
  GROUP BY sequence HAVING COUNT(*) > 1;
-- 결과: 0건 (중복 없음)

SELECT COUNT(*) FROM participation_history
  WHERE campaign_id=<id> AND status='PENDING';
-- 결과: 0건 (Spring Batch 처리 후)
```

### 장애 시나리오 (Spike 중 복합 장애)

```bash
# Spike 진행 중 순서대로 실행
# 1. Redis 샤드 1개 다운 → k6 에러율 유지 확인
# 2. 앱 1대 다운 → ALB failover 확인
# 3. Kafka 브로커 1대 다운 → replication 유지 확인
```

---

## Grafana 공통 확인 항목

> `http://<terraform-mcp-ip>:3000` → campaign 대시보드

| 패널 | 정상 기준 | 이상 징후 |
|------|-----------|-----------|
| API TPS | Phase별 목표 이상 | 목표 미달 → 병목 확인 |
| p95 응답시간 | < 1,000ms (Phase 1 이후) | 증가 추세 → DB 확인 |
| 에러율 | < 1% | 급증 → 로그 확인 |
| redis_queue_size | 0 수렴 | 계속 증가 → Bridge 확인 |
| bridge_drain_duration | 안정적 | 증가 → Kafka 부하 확인 |
| hikaricp_connections_pending | 0 근접 | 증가 → DB 병목 |
| consumer_pending_to_success_latency | 감소 추세 | 증가 → Consumer 확인 |

---

## 병목 판단 가이드

```
TPS 한계 도달 시 어디가 막혔는지 판단:

hikaricp_connections_pending 증가
  → DB 병목 → Phase 3(HikariCP 튜닝) or Phase 7(Aurora)

redis_queue_size 계속 증가
  → Bridge/Kafka 병목 → Phase 4(파티션 증가) 확인

consumer_pending_to_success_latency 증가
  → Consumer 처리 느림 → 파티션/concurrency 증가

API p95 높은데 queue는 낮음
  → PENDING INSERT 병목 → HikariCP 튜닝 or Aurora

에러율 증가
  → 503: Redis 다운 / 429: RateLimit / 400: 재고 소진 확인
```

---

## 비용 관리 (테스트 후 반드시 실행)

```bash
# EC2 콘솔 stop (batch-kafka-app, kafka-1, terraform-mcp)
# RDS 콘솔 stop
# ElastiCache destroy
terraform destroy -target=aws_elasticache_replication_group.redis

# Kafka 3브로커 구성 후
terraform destroy -target=aws_instance.kafka_2
terraform destroy -target=aws_instance.kafka_3
```

---

## 빠른 실행 참조

```bash
# 로컬 테스트
./stress-test/run-test.sh local

# AWS 테스트 (기본값: 재고 10,000 기준 15,000 요청)
CAMPAIGN_ID=1 ./stress-test/run-test.sh prod

# 파라미터 오버라이드
CAMPAIGN_ID=2 TOTAL_REQUESTS=30000 MAX_VUS=2000 DURATION=60 \
  ./stress-test/run-test.sh prod

# k6 직접 실행 (stage 모드 — run-test.sh 미지원)
k6 run \
  -e BASE_URL=http://<ALB> \
  -e CAMPAIGN_ID=<id> \
  --stage 0s:0,10s:2000,60s:2000,10s:0 \
  stress-test/k6-load-test.js
```
