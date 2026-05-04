# 프로젝트 컨텍스트 — 1Million Campaign Orchestration System

> 이 파일은 Claude Code 세션 시작 시 자동으로 로드됩니다.
> 상세 설계: `ARCHITECTURE.md` 참고

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
mcp-server/            ← Python/FastAPI AI 운영 서버 (Phase E, 구현 완료 — 배포 대기)
stress-test/           ← k6 부하 테스트 스크립트
.github/workflows/     ← deploy.yml
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

| 차수 | 핵심 변경 | TPS | 병목 |
|------|----------|-----|------|
| 1차 | 기준선 (pool=10) | 246/s | HikariCP pending ~980 |
| 2차 | pool=20 | 275/s | HikariCP pending ~950 |
| 3차 | pool=40, mcp k6 | 323/s | pool 증가 효과 없음 확인 |
| 4차 | **v3 Redis-first** | 526/s | HikariCP pending 거의 0 |
| 5차 | 파티션 3개 (campaignId 키) | 543/s | Consumer 지연 1.25s |
| 6차 | 파티션 3개 (userId 키) | 550/s | Consumer 지연 200ms |
| 7차 | 3브로커+파티션 10개, 12만 | ~1,150/s | **앱 CPU 90%** |
| 8차 | 3브로커+파티션 10개, 50만 | ~1,220/s | **앱 CPU 80%** |
| 9차 | **ASG 2대**, 50만 | ~2,014/s | 앱 CPU 80% (인스턴스당) |
| 10차 | **writeResultCache 제거 + 배치 INSERT**, 100만 | ~2,613/s | Queue 500K 상한 도달 → 데이터 유실 |
| **11차** | **MAX_QUEUE_SIZE 1M**, 100만 | **~2,442/s** | **정합성 1,000,000 완벽 ✅** |
| **12차** | **ASG 3대 사전 스케일아웃**, 재고 130만/요청 150만 | **~2,507/s** | Queue 1M 초과 → **141,062건 유실** 🔴 |
| **13차** | **Lua 원자화 + MAX_QUEUE_SIZE 1.5M**, 재고 150만/요청 160만 | **~3,747/s** | **정합성 1,500,000 완벽 ✅** |
| **14차** | **동일 조건 재현 검증**, 재고 150만/요청 160만, ASG 3/3/3 | **~3,737/s** | **정합성 1,500,000 완벽 ✅ / 앱 CPU 97%** |

### 11차 테스트 상세 (100만, 최종 성공) ✅
- 조건: TOTAL_REQUESTS=1,200,000, 재고=1,000,000, MAX_VUS=2,000
- TPS ~2,442/s / avg 816ms / p95 2.74s / 5xx: 0 ✅
- DB COUNT = **1,000,000** (정합성 완벽) ✅
- Redis Queue 최대 700K (1M 상한 여유 있음) ✅
- Consumer 지연 7.5~15ms (배치 INSERT 효과) ✅
- Kafka lag 거의 0 (rebalancing 순간 700 스파이크 → 즉시 해소) ✅
- HikariCP pending 거의 0 ✅ / RDS CPU 최대 47% ✅

### 12차 테스트 상세 (130만 재고, 구조적 결함 발견) 🔴
- 조건: TOTAL_REQUESTS=1,500,000, 재고=1,300,000, MAX_VUS=2,000, ASG 3대
- TPS ~2,507/s / avg 1.19s / p95 4.13s / 5xx: 0 ✅
- DB COUNT = **1,158,938** (141,062건 유실) 🔴
- Redis Queue 1M 상한 도달 → LPUSH 실패해도 202 반환 → 유실 (복구 불가)
- 근본 원인: 재고 차감(DECR)과 큐 적재(LPUSH)가 비원자적 → partial failure
- MCP 서버 실시간 GC 이상/Kafka lag 탐지 검증 ✅

### 10차 → 11차 개선 포인트
- 10차: writeResultCache(CME pipeline) 제거 → Kafka lag 9K→거의 0, TPS 2,613/s 달성했으나 Queue 500K 상한으로 **데이터 425K 유실**
- 11차: MAX_QUEUE_SIZE 1M으로 상향 → Queue 700K까지만 차서 유실 0건

### 12차 발견 이슈 → fix/redis-queue-atomic-enqueue 브랜치 ✅ 구현 완료
- check-decr-total.lua + push-queue.lua → check-decr-enqueue.lua 단일 원자 스크립트로 통합
- 큐 만원 시 DECR 자체를 수행하지 않음 → partial failure 원천 차단
- queue key 해시태그 추가 (`queue:campaign:{id}`) → ElastiCache CME 슬롯 제약 해결
- Redis 왕복 2회 → 1회로 감소 (성능 개선 부수효과)

### 13차 테스트 상세 (150만 재고, 원자화 검증) ✅
- 조건: TOTAL_REQUESTS=1,600,000, 재고=1,500,000, MAX_VUS=3,000, ASG 3대
- TPS ~3,747/s / avg 795ms / p95 2.84s / 5xx: 0 ✅
- DB COUNT = **1,500,000** (정합성 완벽) ✅
- 실패 100,000건 = 재고 소진 후 400 (정상)
- 유실 0건 — Lua 원자화 효과 검증 완료 ✅

### 14차 테스트 상세 (150만 재고, 동일 조건 재현 검증) ✅
- 조건: TOTAL_REQUESTS=1,600,000, 재고=1,500,000, MAX_VUS=3,000, ASG 3/3/3
- TPS ~3,737/s / avg 746ms / p95 2.15s / 5xx: 0 ✅
- DB COUNT = **1,500,000** (정합성 완벽) ✅
- 실패 100,000건 = 재고 소진 후 400 (정상)
- Redis Queue 최대 1.2M (1.5M 상한 여유 있음) ✅
- Consumer 지연 ~20ms / Kafka lag 500 스파이크 → 즉시 해소 ✅
- HikariCP pending 거의 0 ✅ / RDS CPU 최대 68.9%
- **앱 CPU 최대 97.3%** — t3.small 3대 한계치 도달 (ASG 추가 스케일아웃 필요 지점)
- Bridge 사이클 초반 1.33분 스파이크 → 이후 안정화 (Queue 적재 > drain 구간)

---

## 구현 이슈 현황

| 이슈 | 상태 |
|------|------|
| #2 공통 인터페이스 (PENDING, sequence, historyId) | ✅ 완료 |
| #3 API 진입 ~ Queue 적재 (A파트) | ✅ 완료 |
| #4 Queue 소비 ~ DB 최종 기록 (B파트) | ✅ 완료 |
| #5 Redis 클러스터링 (ElastiCache CME 3샤드) | ✅ 완료 (terraform apply 완료) |
| #6 Kafka 3-broker KRaft | ✅ 완료 (파티션 10개, RF=3, ISR=3) |
| #7 ASG 수평 확장 | ✅ 완료 (2026-04-27, ASG 2대 운영 중) |
| Spring Batch PENDING 재처리 | 미작성 |
| 모니터링 #22~#25 | ✅ 완료 (Grafana 13패널, 커스텀 메트릭 4종) |
| Phase E MCP 서버 (#62~#66) | ✅ 배포 완료 — 12차 테스트 실시간 운영 검증 ✅ |
| Redis Queue 원자화 (#XX) | ✅ 완료 (fix/redis-queue-atomic-enqueue, 빌드 검증 완료) |

---

## 전체 로드맵

```
[완료] v3 Redis-first, Kafka 3-broker, ElastiCache CME, 8차 테스트 (50만 TPS ~1,220/s)
[완료] Phase A — ASG Terraform (asg.tf, codedeploy.tf, EC2 SD, min=2 운영 중)
[완료] 9차 테스트 (ASG 2대, 50만 TPS ~2,014/s)
[완료] Phase B — 100만 트래픽 테스트 성공 (11차, TPS ~2,442/s, 정합성 1,000,000 ✅)
       - writeResultCache 제거 (CME pipeline 병목)
       - Consumer jdbcTemplate.batchUpdate() 배치 INSERT
       - MAX_QUEUE_SIZE 500K → 1M (데이터 유실 방지)
       - terraform-mcp t3.large (k6 OOM 방지)
[예정] Phase C — API 엔드포인트 v3 정리
[예정] Phase D — Spring Batch 안전망
[완료] Phase E — MCP 서버 (AI 자율 운영)
       - terraform-mcp 배포 완료, Claude Desktop MCP 연결 (mcp-remote@latest)
       - 12차 테스트 중 실시간 GC 이상/Kafka lag 탐지 검증 ✅
       - CI/CD 파이프라인 미구성 (mcp-server/** 트리거 없음, 수동 배포 유지)
[완료] fix/redis-queue-atomic-enqueue
       - check-decr-enqueue.lua 단일 Lua 원자화 (DECR + LPUSH)
       - 큐 만원 시 DECR 수행 안 함 → partial failure 원천 차단
       - queue key 해시태그 통일 → ElastiCache CME 슬롯 제약 해결
       - Redis 왕복 2회 → 1회 (성능 개선 부수효과)
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
| EC2 (MCP) | terraform-mcp (t3.large, public_2a, Prometheus+Grafana+redis-exporter+kafka-exporter 실행 중) |
| RDS | batch-kafka-db (MySQL 8.0.44, db.t3.micro) |
| ALB | alb-batch-kafka-api → tg-api-8080 |
| ElastiCache | CME 3샤드 (Valkey 7.2, cache.t3.micro × 6) |
| ECR | batch-kafka-system |
| S3 (배포) | batch-kafka-deploy-631124976154 |
| S3 (tfstate) | campaign-terraform-state-631124976154 |
| CodeDeploy | App: batch-kafka-app / DG: batch-kafka-prod-dg (ASG 연결, IaC 완료) |
| SSM | /batch-kafka/prod/* |

---

## 모니터링 구성 (terraform-mcp)

- Prometheus + Grafana + redis-exporter + kafka-exporter + **mcp-server** 모두 Docker 컨테이너
- **Docker monitoring 네트워크**: 컨테이너 이름 기반 통신 (IP 변경 무관)
  ```bash
  # 컨테이너 재생성 시 네트워크 연결 필수
  sudo docker network connect monitoring <container>
  ```
- **prometheus.yml 타겟** (IP 아닌 컨테이너 이름):
  - spring-boot: ec2_sd_configs (ASG 동적 감지, :8080)
  - kafka-exporter: `kafka-exporter:9308`
  - redis-exporter: `redis-exporter:9121`
- prometheus.yml 위치: `/home/ec2-user/prometheus.yml`
- redis-exporter는 terraform-mcp 단독 실행 (ASG 중복 수집 방지)

---

## CI/CD

`.github/workflows/deploy.yml` — main 브랜치 + `app/campaign-core/**` 변경 시 트리거
OIDC → ECR → S3 → CodeDeploy (`CodeDeployDefault.OneAtATime`)

---

## 인프라 ON/OFF 순서

### 켤 때 (ElastiCache destroy 후 재apply 시)
```
1. terraform apply (ElastiCache + SSM 자동 갱신, ~10~15분)
2. RDS 시작 (3~5분 대기)
3. kafka-1/2/3 시작
4. terraform-mcp 시작
5. terraform-mcp에서 redis-exporter 재실행 (SSM에서 새 엔드포인트 자동 읽음):
   sudo docker rm -f redis-exporter
   sudo docker run -d --name redis-exporter --restart unless-stopped -p 9121:9121 \
     --network monitoring \
     -e REDIS_ADDR="$(aws ssm get-parameter --name /batch-kafka/prod/REDIS_EXPORTER_ADDR \
     --with-decryption --query Parameter.Value --output text)" \
     -e REDIS_EXPORTER_IS_CLUSTER=true oliver006/redis_exporter
   sudo docker network connect monitoring redis-exporter
6. ASG 콘솔에서 desired=2, min=2, max=3 (terraform apply와 독립)
```

### 켤 때 (EC2 재시작만, ElastiCache 유지 시)
```
1. RDS 시작
2. kafka-1/2/3 시작
3. terraform-mcp 시작 (--restart unless-stopped로 컨테이너 자동 복구)
4. mcp-server 컨테이너 확인: `sudo docker ps | grep mcp-server` (미실행 시 아래 mcp-server 배포 섹션 참고)
5. ASG 콘솔에서 desired=2, min=2, max=3
```

### 끌 때
```
1. ASG 콘솔에서 desired=0, min=0, max=0
2. kafka-1/2/3 중지
3. terraform-mcp 중지
4. RDS 중지
5. (장기 미사용 시) 로컬에서:
   terraform destroy -target=aws_elasticache_replication_group.redis
   terraform destroy -target=aws_ssm_parameter.redis_cluster_nodes
   terraform destroy -target=aws_ssm_parameter.redis_exporter_addr
```

> ASG 인스턴스는 콘솔에서 직접 중지 금지 → desired=0으로만
> ElastiCache destroy → apply 시 SSM(SPRING_DATA_REDIS_CLUSTER_NODES) 자동 갱신
> ASG min/max는 ignore_changes로 보호 — terraform apply가 용량을 건드리지 않음

---

## 테스트 실행 (terraform-mcp에서)

```bash
# 캠페인 생성
curl -X POST $BASE_URL/api/admin/campaigns \
  -H "Content-Type: application/json" \
  -d '{"name":"load-test-1M","totalStock":1000000,"startDate":"2026-04-28","endDate":"2026-12-31"}'

# Step 1 — 10만 검증 (배치 INSERT 효과 확인)
CAMPAIGN_ID=<id> TOTAL_REQUESTS=100000 MAX_VUS=2000 DURATION=300 \
  bash ~/1milion-campaign-orchestration-system/stress-test/run-test.sh prod

# 검증 포인트: 로그에서 records.size() 분포 확인
sudo docker logs <container> --follow 2>&1 | grep "batch processed"

# Step 2 — 10만 통과 후 100만 테스트
CAMPAIGN_ID=<id> TOTAL_REQUESTS=1200000 MAX_VUS=2000 DURATION=3600 \
  bash ~/1milion-campaign-orchestration-system/stress-test/run-test.sh prod
```

BASE_URL: `http://alb-batch-kafka-api-1351817547.ap-northeast-2.elb.amazonaws.com`

---

## mcp-server 배포 (terraform-mcp에서 수동)

```bash
# 최초 배포 또는 코드 업데이트 시
cd ~/1milion-campaign-orchestration-system
git pull

sudo docker build -t mcp-server:latest ./mcp-server

sudo docker run -d \
  --name mcp-server \
  --restart unless-stopped \
  --network monitoring \
  -p 8000:8000 \
  -e SLACK_WEBHOOK_URL="$(aws ssm get-parameter --name /batch-kafka/prod/SLACK_WEBHOOK_URL \
    --with-decryption --query Parameter.Value --output text)" \
  -e BATCH_API_URL="http://alb-batch-kafka-api-1351817547.ap-northeast-2.elb.amazonaws.com" \
  -e BATCH_CAMPAIGN_ID="<캠페인ID>" \
  mcp-server:latest

# 검증
curl http://localhost:8000/health
sudo docker logs mcp-server --follow
```

MCP 도구 7개 (Claude에서 호출 가능):
- `get_monitor_status` — cooldown 상태 조회
- `run_check` — P1/P2/P3 수동 실행
- `query_prometheus` — PromQL instant 쿼리
- `query_prometheus_range` — 구간 메트릭 조회 (테스트 후 분석용)
- `get_test_report` — 테스트 구간 핵심 메트릭 요약
- `reset_cooldown` — 쿨다운 초기화
- `trigger_consistency_check` — 정합성 검사 즉시 실행

---

## 면접 핵심 멘트

**프로젝트 소개**: "Redis Lua로 병목 원인을 찾고 해결한 뒤 재실험으로 검증, 데이터 기반으로 트레이드오프를 결정한 실험 중심 프로젝트. 최종적으로 100만 트래픽, 정합성 1,000,000건, 5xx 0건 달성"

**CI/CD**: "OIDC 기반 키 없는 AWS 인증, SSH 차단 + SSM Session Manager, CodeDeploy OneAtATime 무중단 배포"

**한계 인지**: "단일 인스턴스 CPU 한계 → ASG 수평 확장, 데이터로 근거 제시. TPS 1,220→2,014/s (+65%) 확인"

**Consumer 병목 개선**: "Kafka batch listener로 받아도 DB INSERT가 1건씩이면 소용없다는 걸 로그로 확인 후, JdbcTemplate.batchUpdate + rewriteBatchedStatements=true로 DB 왕복 N→1로 줄임. Consumer 지연 200ms → 7.5ms"

**데이터 유실 발견 및 해결**: "10차 테스트에서 DB 정합성 검증 중 574,888건만 INSERT된 것 발견. Redis Queue 500K 상한 초과 시 LPUSH 실패해도 202 반환하는 구조적 문제. MAX_QUEUE_SIZE 1M으로 상향해 11차에서 정합성 완벽 달성"

**구조적 결함 발견 및 해결 (12차→13차)**: "12차에서 재고 차감(DECR)과 큐 적재(LPUSH)가 비원자적이라 141K 유실 발생. 재고는 차감됐는데 큐 적재 실패 → 사용자는 202 받았지만 DB에 없음 → PENDING/DLQ 기록도 없어 복구 불가. check-decr-enqueue.lua 단일 Lua로 원자화 후 13차 재고 150만 테스트에서 DB COUNT 1,500,000 정합성 완벽 달성. Redis 왕복 2회 → 1회 성능 개선 부수효과"

**모니터링**: "Prometheus + Grafana 커스텀 메트릭 4종(Bridge 드레인 속도/사이클, Consumer 지연, Redis Queue 적재량). Docker monitoring 네트워크로 컨테이너 이름 기반 DNS — IP 관리 불필요"

**MCP 서버 (Phase E)**: "Grafana 대시보드를 사람이 직접 보던 수동 감시를 자동화. Prometheus/CloudWatch 30초 폴링 → P1/P2/P3 임계값 비교 → Slack 알림. Claude MCP 도구 7개로 운영 중 즉시 쿼리 가능. AI는 탐지와 설명만, 실행은 사람이 판단하는 구조로 설계"
