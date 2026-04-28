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
mcp-server/            ← Python/FastAPI AI 운영 서버 (Phase E, 미작성)
stress-test/           ← k6 부하 테스트 스크립트
.github/workflows/     ← deploy.yml
```

---

## v3 아키텍처 (현재 코드)

```
POST /api/campaigns/{id}/participation
  1. RateLimitService     SET NX EX 10
  2. Redis Lua            SISMEMBER + DECR + GET total (check-decr-total.lua)
     remaining < 0  → 보상 INCR + 400
     remaining == 0 → DB CLOSED + active flag DEL
  3. sequence = total - remaining (선착순 확정)
  4. RedisQueueService    LPUSH queue:campaign:{id}  (MAX_QUEUE_SIZE=1M)
  5. 202 반환 (DB 미접촉)

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

### 11차 테스트 상세 (100만, 최종 성공) ✅
- 조건: TOTAL_REQUESTS=1,200,000, 재고=1,000,000, MAX_VUS=2,000
- TPS ~2,442/s / avg 816ms / p95 2.74s / 5xx: 0 ✅
- DB COUNT = **1,000,000** (정합성 완벽) ✅
- Redis Queue 최대 700K (1M 상한 여유 있음) ✅
- Consumer 지연 7.5~15ms (배치 INSERT 효과) ✅
- Kafka lag 거의 0 (rebalancing 순간 700 스파이크 → 즉시 해소) ✅
- HikariCP pending 거의 0 ✅ / RDS CPU 최대 47% ✅

### 10차 → 11차 개선 포인트
- 10차: writeResultCache(CME pipeline) 제거 → Kafka lag 9K→거의 0, TPS 2,613/s 달성했으나 Queue 500K 상한으로 **데이터 425K 유실**
- 11차: MAX_QUEUE_SIZE 1M으로 상향 → Queue 700K까지만 차서 유실 0건

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
[예정] Phase E — MCP 서버 (AI 자율 운영)
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

- Prometheus + Grafana + redis-exporter + kafka-exporter 모두 Docker 컨테이너
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
4. ASG 콘솔에서 desired=2, min=2, max=3
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

## 면접 핵심 멘트

**프로젝트 소개**: "Redis Lua로 병목 원인을 찾고 해결한 뒤 재실험으로 검증, 데이터 기반으로 트레이드오프를 결정한 실험 중심 프로젝트. 최종적으로 100만 트래픽, 정합성 1,000,000건, 5xx 0건 달성"

**CI/CD**: "OIDC 기반 키 없는 AWS 인증, SSH 차단 + SSM Session Manager, CodeDeploy OneAtATime 무중단 배포"

**한계 인지**: "단일 인스턴스 CPU 한계 → ASG 수평 확장, 데이터로 근거 제시. TPS 1,220→2,014/s (+65%) 확인"

**Consumer 병목 개선**: "Kafka batch listener로 받아도 DB INSERT가 1건씩이면 소용없다는 걸 로그로 확인 후, JdbcTemplate.batchUpdate + rewriteBatchedStatements=true로 DB 왕복 N→1로 줄임. Consumer 지연 200ms → 7.5ms"

**데이터 유실 발견 및 해결**: "10차 테스트에서 DB 정합성 검증 중 574,888건만 INSERT된 것 발견. Redis Queue 500K 상한 초과 시 LPUSH 실패해도 202 반환하는 구조적 문제. MAX_QUEUE_SIZE 1M으로 상향해 11차에서 정합성 완벽 달성"

**모니터링**: "Prometheus + Grafana 커스텀 메트릭 4종(Bridge 드레인 속도/사이클, Consumer 지연, Redis Queue 적재량). Docker monitoring 네트워크로 컨테이너 이름 기반 DNS — IP 관리 불필요"
