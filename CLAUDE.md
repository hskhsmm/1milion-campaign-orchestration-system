# 프로젝트 컨텍스트 — 1Million Campaign Orchestration System

> 이 파일은 Claude Code 세션 시작 시 자동으로 로드됩니다.
> 상세 설계: `ARCHITECTURE.md` | 장애 시나리오: `FAILURE_TEST_SCENARIOS.md`

---

## 프로젝트 개요

선착순 캠페인 참여 시스템, 100만 트래픽 + AI 자율 운영 목표.

- **레포**: https://github.com/hskhsmm/1milion-campaign-orchestration-system
- **담당자**: 데브옵스 직무 취준생, 면접 준비 중

---

## 레포 구조

```
app/campaign-core/     ← Spring Boot 앱 (v3 Redis-first)
infra/                 ← Terraform IaC
mcp-server/            ← Python/FastAPI AI 운영 서버 (Phase E, 배포 완료)
stress-test/           ← k6 부하 테스트 스크립트
.github/workflows/     ← deploy.yml
FAILURE_TEST_SCENARIOS.md ← 장애 시나리오 7개
```

---

## v3 아키텍처 (현재 코드)

```
POST /api/campaigns/{id}/participation
  1. RateLimitService     SET NX EX 10
  2. Redis Lua            큐 만원 체크 + DECR + LPUSH + GET total (check-decr-enqueue.lua) — 단일 원자 실행
     queue full(-998) → 재고 차감 없이 429 반환  (MAX_QUEUE_SIZE=1,500,000)
     remaining < 0   → 400 반환
     remaining == 0  → DB CLOSED + active flag DEL
  3. sequence = total - remaining (Lua 내부에서 계산 후 message에 포함)
  4. 202 반환 (DB 미접촉)

ParticipationBridge (@Scheduled 100ms)
  → SMEMBERS active:campaigns → RPOP → Kafka publish (userId 파티션 키)
  → 동적 batchSize: <10K→500 / <100K→1000 / >=100K→2000

Kafka Consumer (concurrency=10, 파티션 10개, RF=3, min.ISR=2)
  → jdbcTemplate.batchUpdate() INSERT IGNORE 배치 처리 (멱등성)
  → 배치 실패 시 단건 폴백 + DLQ
  (Redis 결과 캐시 제거 — CME pipeline 병목 원인이었음)
```

---

## 부하 테스트 결과 요약

| 차수 | 핵심 변경 | TPS | 병목/결과 |
|------|----------|-----|---------|
| 1~3차 | pool 튜닝 (10→40) | 246~323/s | HikariCP pending ~980 |
| 4차 | **v3 Redis-first** | 526/s | HikariCP pending 거의 0 |
| 5~6차 | 파티션 3개, 파티션 키 변경 | 543~550/s | Consumer 지연 1.25s→200ms |
| 7~8차 | 3브로커+파티션 10개 | ~1,150~1,220/s | 앱 CPU 80~90% |
| 9차 | **ASG 2대** | ~2,014/s | 앱 CPU 80% |
| 10차 | writeResultCache 제거 + 배치 INSERT | ~2,613/s | Queue 500K 상한 → **425K 유실** |
| **11차** | **MAX_QUEUE_SIZE 1M**, 재고 100만 | **~2,442/s** | **정합성 1,000,000 완벽** ✅ |
| **12차** | ASG 3대, 재고 130만 | **~2,507/s** | Queue 1M 초과 → **141,062건 유실** 🔴 |
| **13차** | **Lua 원자화 + MAX_QUEUE_SIZE 1.5M**, 재고 150만 | **~3,747/s** | **정합성 1,500,000 완벽** ✅ |
| **14차** | 동일 조건 재현 검증, ASG 3/3/3 | **~3,737/s** | **정합성 1,500,000 완벽 ✅ / 앱 CPU 97%** |

### 12차 구조적 결함 발견 → 13차 원자화 해결

**12차** (130만 재고, 150만 요청, ASG 3대): DB COUNT = 1,158,938 → **141,062건 유실** 🔴
- 근본 원인: 재고 차감(DECR)과 큐 적재(LPUSH)가 비원자적 → Queue full 시 DECR은 됐지만 LPUSH 실패 → 202 반환했지만 DB에 없음, 복구 불가
- MCP 서버 실시간 GC 이상/Kafka lag 탐지 검증 ✅

**수정**: check-decr-enqueue.lua 단일 Lua 원자화
- 큐 만원 시 DECR 자체 미실행 → partial failure 원천 차단
- queue key 해시태그 (`queue:campaign:{id}`) → ElastiCache CME 슬롯 제약 해결
- Redis 왕복 2회 → 1회 (성능 개선 부수효과)

**13차** (150만 재고, 160만 요청, ASG 3대): DB COUNT = **1,500,000** ✅, 유실 0건

### 14차 테스트 상세 (최신, 동일 조건 재현 검증) ✅
- 조건: TOTAL_REQUESTS=1,600,000, 재고=1,500,000, MAX_VUS=3,000, ASG 3/3/3
- TPS ~3,737/s / avg 746ms / p95 2.15s / 5xx: 0 ✅
- DB COUNT = **1,500,000** (정합성 완벽) ✅ / 실패 100,000건 = 재고 소진 후 400 (정상)
- Redis Queue 최대 1.2M (1.5M 상한 여유 있음) ✅
- Consumer 지연 ~20ms / Kafka lag 500 스파이크 → 즉시 해소 ✅
- HikariCP pending 거의 0 ✅ / RDS CPU 최대 68.9%
- **앱 CPU 최대 97.3%** — t3.small 3대 한계치 도달 (ASG 추가 스케일아웃 필요 지점)
- Bridge 사이클 초반 1.33분 스파이크 → 이후 안정화 (Queue 적재 > drain 구간)

### VUS 최적화 인사이트
TPS = VUS / 응답시간 (Little's Law) — VUS가 높다고 무조건 TPS가 높아지지 않음.

| VUS | 응답시간 | TPS | CPU | 용도 |
|-----|---------|-----|-----|------|
| 500 | ~15ms | ~1,300 | ~30% | 장애 테스트 부하 유지 |
| 1,500 | ~300ms | ~2,500 | ~50% | 안정 운영 시뮬레이션 |
| 3,000 | ~746ms | ~3,737 | ~97% | 최대 한계 측정 |

- VUS=3,000은 t3.small 3대를 CPU 97%까지 포화시키는 숫자 (서버 스펙이 커지면 더 높은 VUS 필요)
- 장애 테스트용 적정 VUS: 500 (Kafka lag, 5xx 관찰에 충분)
- 안정 운영 조건 탐색: VUS 500→1000→1500→2000 단계적 올리며 CPU 70% 이하 구간 확인

---

## 장애 시나리오 테스트 결과

| # | 테스트 | 결과 | 비고 |
|---|--------|------|------|
| T01 | 중복 요청 차단 (RateLimitService) | ✅ 통과 | 202→429→409 확인 |
| T02 | 재고 소진 400 | ✅ 통과 | diff=0, DB COUNT=2 정합성 완벽 |
| T03 | Queue 만원 429 | ✅ DB 증거로 대체 | campaign 24 vs 27 비교 |
| T04 | ConsistencyJob MISSING_REDIS_STOCK | ✅ 통과 | restoreStock=20 복구 확인 |
| T05 | Kafka 브로커 1대 장애 | ✅ 통과 | lag 스파이크→즉시 해소, 5xx 없음 |
| T06 | RDS 다운 → DLQ → 재처리 | ✅ 통과 | SG 3306 삭제/복구, 구조적 허점 2건 발견 및 수정 |
| T07 | ASG 인스턴스 1대 종료 | ✅ 통과 | TPS 80% 급락→3~4분 완전 복구, 5xx 0건, diff=0 |

### T03 DB 증거 (코드 수정 없이 대체)
```sql
SELECT c.id, c.total_stock, c.status, COUNT(ph.id) AS success_count,
       c.total_stock - COUNT(ph.id) AS lost_count
FROM campaign c LEFT JOIN participation_history ph
  ON ph.campaign_id = c.id AND ph.status = 'SUCCESS'
WHERE c.id IN (24, 27) GROUP BY c.id, c.total_stock, c.status;
```
| id | total_stock | status | success_count | lost_count |
|----|-------------|--------|---------------|------------|
| 24 | 1,300,000 | CLOSED | 1,158,938 | **141,062** ← 12차 유실 |
| 27 | 1,500,000 | CLOSED | 1,500,000 | **0** ← 13차 완벽 ✅ |

### T01 추가 수정 — 영구 중복 참여 차단
RateLimitService TTL(10초) 만료 후 동일 userId 재요청 시 Redis stock 추가 차감 버그 발견 및 수정.
- `check-decr-enqueue.lua`: KEYS[5](participated 키) EXISTS 체크 → -997 반환, LPUSH 성공 시 SET '1'
- `RedisStockService`: ALREADY_PARTICIPATED(-997L), getParticipatedKey() 추가
- `ParticipationService`: ALREADY_PARTICIPATED → DuplicateParticipationException(409)
- 브랜치: `fix/prevent-duplicate-participation-after-ratelimit-ttl` (머지 완료)

---

## 구현 이슈 현황

| 이슈 | 상태 |
|------|------|
| #2~#4 공통 인터페이스, A/B파트 | ✅ 완료 |
| #5 Redis 클러스터링 (ElastiCache CME 3샤드) | ✅ 완료 |
| #6 Kafka 3-broker KRaft (파티션 10개, RF=3) | ✅ 완료 |
| #7 ASG 수평 확장 (min=2/max=3) | ✅ 완료 |
| 모니터링 (Grafana 13패널, 커스텀 메트릭 4종) | ✅ 완료 |
| Phase E MCP 서버 (#62~#66) | ✅ 배포 완료 — 12차 실시간 운영 검증 ✅ |
| Redis Queue 원자화 (check-decr-enqueue.lua) | ✅ 완료 |
| Spring Batch PENDING 재처리 | ✅ 완료 (PendingRecoveryJobConfig) |
| 영구 중복 참여 차단 (participated 키) | ✅ 완료 (fix/prevent-duplicate-participation-after-ratelimit-ttl) |
| remaining==0 DB 호출 500 전파 차단 | ✅ 완료 (T06 테스트 중 발견, try-catch로 수정) |

---

## 전체 로드맵

```
[완료] v3 Redis-first → ASG 수평 확장 → 14차 테스트 (150만 정합성 완벽, TPS ~3,737/s)
[완료] Phase E — MCP 서버 (AI 자율 운영, 30초 폴링, P1/P2/P3, Slack 알림)
[완료] fix/redis-queue-atomic-enqueue (Lua 원자화, partial failure 차단)
[완료] fix/prevent-duplicate-participation-after-ratelimit-ttl (영구 중복 참여 차단)
[완료] 장애 테스트 T01~T07 전체 통과
[예정] VUS 단계별 부하 테스트 (안정 운영 조건 탐색: VUS 500→1500→2000)
```

---

## AWS 리소스

| 리소스 | 이름/값 |
|--------|---------|
| Account ID | 631124976154 |
| Region | ap-northeast-2 |
| VPC | vpc-02bacd8c658dc632e (172.31.0.0/16) |
| ASG (앱) | batch-kafka-app-asg (t3.small, min=2/max=3, private_app_2a+2b) |
| EC2 (Kafka) | kafka-1/2/3 (t3.small, public 2a/2b/2c) |
| EC2 (MCP) | terraform-mcp (t3.large, public_2a, Prometheus+Grafana+redis-exporter+kafka-exporter+mcp-server) |
| RDS | batch-kafka-db (MySQL 8.0.44, db.t3.micro) |
| ALB | alb-batch-kafka-api → tg-api-8080 |
| ElastiCache | CME 3샤드 (Valkey 7.2, cache.t3.micro × 6) |
| ECR | batch-kafka-system |
| S3 (배포) | batch-kafka-deploy-631124976154 |
| S3 (tfstate) | campaign-terraform-state-631124976154 |
| CodeDeploy | App: batch-kafka-app / DG: batch-kafka-prod-dg (ASG 연결) |
| SSM | /batch-kafka/prod/* |

---

## 모니터링 구성 (terraform-mcp)

- Prometheus + Grafana + redis-exporter + kafka-exporter + **mcp-server** 모두 Docker 컨테이너
- **Docker monitoring 네트워크**: 컨테이너 이름 기반 통신 (IP 변경 무관)
  ```bash
  sudo docker network connect monitoring <container>
  ```
- **prometheus.yml 타겟**: spring-boot (ec2_sd_configs, :8080) / kafka-exporter:9308 / redis-exporter:9121
- prometheus.yml 위치: `/home/ec2-user/prometheus.yml`

---

## CI/CD

`.github/workflows/deploy.yml` — main 브랜치 + `app/campaign-core/**` 변경 시 트리거
OIDC → ECR → S3 → CodeDeploy (`CodeDeployDefault.OneAtATime`)

---

## 인프라 ON/OFF 순서

### 켤 때 (EC2 재시작만, ElastiCache 유지)
```
1. RDS 시작
2. kafka-1/2/3 시작
3. terraform-mcp 시작 (--restart unless-stopped로 컨테이너 자동 복구)
4. mcp-server 확인: sudo docker ps | grep mcp-server
5. ASG 콘솔에서 desired=2, min=2, max=3
```

### 켤 때 (ElastiCache destroy 후 재apply)
```
1. terraform apply (ElastiCache + SSM 자동 갱신, ~15분)
2. RDS → kafka-1/2/3 → terraform-mcp 순서로 시작
3. redis-exporter 재실행 (SSM에서 새 엔드포인트 읽음):
   sudo docker rm -f redis-exporter
   sudo docker run -d --name redis-exporter --restart unless-stopped -p 9121:9121 \
     --network monitoring \
     -e REDIS_ADDR="$(aws ssm get-parameter --name /batch-kafka/prod/REDIS_EXPORTER_ADDR \
     --with-decryption --query Parameter.Value --output text)" \
     -e REDIS_EXPORTER_IS_CLUSTER=true oliver006/redis_exporter
4. ASG 콘솔에서 desired=2, min=2, max=3
```

### 끌 때
```
1. ASG desired=0, min=0, max=0
2. kafka-1/2/3 중지 → terraform-mcp 중지 → RDS 중지
3. (장기 미사용 시) terraform destroy -target=aws_elasticache_replication_group.redis
```

> ASG 인스턴스는 콘솔 직접 중지 금지 → desired=0으로만
> ASG min/max는 ignore_changes로 보호 — terraform apply가 용량 건드리지 않음

---

## 테스트 실행 (terraform-mcp에서)

```bash
BASE_URL=http://alb-batch-kafka-api-1351817547.ap-northeast-2.elb.amazonaws.com

# 캠페인 생성
curl -X POST $BASE_URL/api/admin/campaigns \
  -H "Content-Type: application/json" \
  -d '{"name":"load-test-1.5M","totalStock":1500000,"startDate":"2026-05-01","endDate":"2026-12-31"}'

# MCP 서버에 캠페인 ID 설정 (재시작 불필요)
curl -X PUT http://localhost:8000/config/campaign -H "Content-Type: application/json" -d '{"campaign_id": <id>}'

# 150만 테스트
CAMPAIGN_ID=<id> TOTAL_REQUESTS=1600000 MAX_VUS=3000 DURATION=3600 \
  bash ~/1milion-campaign-orchestration-system/stress-test/run-test.sh prod
```

---

## mcp-server 배포 (terraform-mcp에서 수동)

```bash
cd ~/1milion-campaign-orchestration-system && git pull
sudo docker build -t mcp-server:latest ./mcp-server

sudo docker run -d \
  --name mcp-server \
  --restart unless-stopped \
  --network monitoring \
  -p 8000:8000 \
  -e SLACK_WEBHOOK_URL="$(aws ssm get-parameter --name /batch-kafka/prod/SLACK_WEBHOOK_URL \
    --with-decryption --query Parameter.Value --output text)" \
  -e BATCH_API_URL="http://alb-batch-kafka-api-1351817547.ap-northeast-2.elb.amazonaws.com" \
  mcp-server:latest

# 검증
curl http://localhost:8000/health

# 캠페인 ID는 재시작 없이 런타임 변경 가능
curl -X PUT http://localhost:8000/config/campaign -H "Content-Type: application/json" -d '{"campaign_id": <id>}'
curl http://localhost:8000/config/campaign
```

MCP 도구 7개: `get_monitor_status` / `run_check` / `query_prometheus` / `query_prometheus_range` / `get_test_report` / `reset_cooldown` / `trigger_consistency_check`

---

## 면접 핵심 멘트

**프로젝트 소개**: "Redis Lua로 병목 원인을 찾고 해결한 뒤 재실험으로 검증, 데이터 기반으로 트레이드오프를 결정한 실험 중심 프로젝트. 최종 150만 트래픽, 정합성 1,500,000건, 5xx 0건, TPS ~3,737/s 달성"

**CI/CD**: "OIDC 기반 키 없는 AWS 인증, SSH 차단 + SSM Session Manager, CodeDeploy OneAtATime 무중단 배포"

**한계 인지**: "단일 인스턴스 CPU 한계 → ASG 수평 확장, 데이터로 근거 제시. TPS 1,220→2,014/s (+65%) 확인. 14차에서 앱 CPU 97% 도달 — ASG 추가 스케일아웃 필요 지점 확인"

**Consumer 병목 개선**: "Kafka batch listener로 받아도 DB INSERT가 1건씩이면 소용없다는 걸 로그로 확인 후, JdbcTemplate.batchUpdate + rewriteBatchedStatements=true로 DB 왕복 N→1로 줄임. Consumer 지연 200ms → 7.5ms"

**데이터 유실 발견 및 해결**: "10차에서 Queue 500K 상한 초과 시 LPUSH 실패해도 202 반환하는 구조적 문제 발견. MAX_QUEUE_SIZE 1M 상향으로 11차 정합성 완벽 달성"

**구조적 결함 발견 및 해결 (12차→13차)**: "12차에서 재고 차감(DECR)과 큐 적재(LPUSH)가 비원자적이라 141K 유실 발생. 재고는 차감됐는데 큐 적재 실패 → 사용자는 202 받았지만 DB에 없음 → PENDING/DLQ 기록도 없어 복구 불가. check-decr-enqueue.lua 단일 Lua로 원자화 후 13차 DB COUNT 1,500,000 정합성 완벽 달성. Redis 왕복 2회 → 1회 성능 개선 부수효과"

**모니터링**: "Prometheus + Grafana 커스텀 메트릭 4종(Bridge 드레인 속도/사이클, Consumer 지연, Redis Queue 적재량). Docker monitoring 네트워크로 컨테이너 이름 기반 DNS — IP 관리 불필요"

**MCP 서버 (Phase E)**: "Grafana 대시보드를 사람이 직접 보던 수동 감시를 자동화. Prometheus/CloudWatch 30초 폴링 → P1/P2/P3 임계값 비교 → Slack 알림. Claude MCP 도구 7개로 운영 중 즉시 쿼리 가능. AI는 탐지와 설명만, 실행은 사람이 판단하는 구조로 설계"

**장애 테스트 (T01~T07 전체 통과)**: "7개 시나리오 전체 실제 AWS 환경에서 검증 완료. T03은 campaign 24(유실 141,062) vs campaign 27(유실 0) DB 쿼리로 대체 증명. T05에서 VUS=500으로도 Kafka lag 스파이크 → 즉시 해소 확인. T07 인스턴스 terminate 후 TPS 80% 급락 → 3~4분 완전 복구, 5xx 0건, DB diff=0"

**T06 구조적 허점 발견 및 수정**: "RDS SG 3306 삭제로 장애 주입 → 5xx 발생 → 복구 후 정상화 확인. 두 가지 허점 발견: ① remaining==0(마지막 재고 소진) 시 캠페인 CLOSED 처리를 위해 동기 DB 호출이 남아있어 RDS 장애 시 500 전파 → try-catch로 수정, Redis Lua에서 이미 active flag DEL하므로 ConsistencyJob fallback. ② actuator/health가 DB 상태를 포함해 ALB TG가 unhealthy 처리 → Redis-first API인데도 5xx 발생 (개선 포인트). Consumer hasTransientFailure ack 보류 → SG 복구 후 Kafka 재전달로 자동 재처리 동작 확인"

**중복 참여 버그 발견 및 수정**: "장애 테스트 T01 중 RateLimitService TTL 만료 후 동일 userId 재참여 시 Redis stock 이중 차감 비즈니스 결함 발견. Lua 스크립트에 participated 영구 이력 키 추가로 원천 차단. DB UNIQUE 제약은 있었지만 재고 차감은 막지 못했던 구조적 허점"
