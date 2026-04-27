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
  4. RedisQueueService    LPUSH queue:campaign:{id}
  5. 202 반환 (DB 미접촉)

ParticipationBridge (@Scheduled 100ms)
  → SMEMBERS active:campaigns → RPOP → Kafka publish (userId 파티션 키)
  → 동적 batchSize: <10K→500 / <100K→1000 / >=100K→2000

Kafka Consumer (concurrency=10, 파티션 10개, RF=3, min.ISR=2)
  → INSERT IGNORE (멱등성) → Redis 결과 캐시
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

### 8차 테스트 상세 (현재 한계)
- 조건: TOTAL_REQUESTS=600,000, 재고=500,000, MAX_VUS=2,000, DURATION=1,200s
- HikariCP pending: 거의 0 ✅ / RDS CPU: 9.44% ✅ / 5xx: 0 ✅
- **확정 병목: 앱 CPU 80~90% 고착 — t3.small 단일 인스턴스 한계**
- 다음 단계: ASG 수평 확장 (스케일업 대비 비용 2배 + SPOF 제거 + 탄력성)

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

## 다음 작업 — Phase B: 9차 부하 테스트 (100만)

**목표**: ASG 2대 환경에서 100만 트래픽 처리 검증. TPS ~2,400/s, CPU 각 ~40% 분산 기대.

### 전체 로드맵
```
[완료] v3 Redis-first, Kafka 3-broker, ElastiCache CME, 8차 테스트 (50만 TPS ~1,220/s)
[완료] Phase A — ASG Terraform (asg.tf, codedeploy.tf, EC2 SD, min=2 운영 중)
[진행] Phase B — 9차 테스트 (100만)
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
| EC2 (MCP) | terraform-mcp (t3.small, public_2a, Prometheus+Grafana 실행 중) |
| RDS | batch-kafka-db (MySQL 8.0.44, db.t3.micro) |
| ALB | alb-batch-kafka-api → tg-api-8080 |
| ElastiCache | CME 3샤드 (Valkey 7.2, cache.t3.micro × 6) |
| ECR | batch-kafka-system |
| S3 (배포) | batch-kafka-deploy-631124976154 |
| S3 (tfstate) | campaign-terraform-state-631124976154 |
| CodeDeploy | App: batch-kafka-app / DG: batch-kafka-prod-dg (ASG 연결, IaC 완료) |
| SSM | /batch-kafka/prod/* |

---

## CI/CD

`.github/workflows/deploy.yml` — main 브랜치 + `app/campaign-core/**` 변경 시 트리거
OIDC → ECR → S3 → CodeDeploy (`CodeDeployDefault.AllAtOnce`)

---

## 테스트 실행 (terraform-mcp에서)

```bash
# 캠페인 생성
curl -X POST $BASE_URL/api/admin/campaigns \
  -H "Content-Type: application/json" \
  -d '{"name":"load-test","totalStock":1000000,"startDate":"2026-04-27","endDate":"2026-12-31"}'

# 테스트 실행
CAMPAIGN_ID=<id> TOTAL_REQUESTS=1200000 MAX_VUS=3000 DURATION=2400 \
  bash ~/1milion-campaign-orchestration-system/stress-test/run-test.sh prod
```

BASE_URL: `http://alb-batch-kafka-api-1351817547.ap-northeast-2.elb.amazonaws.com`

---

## 면접 핵심 멘트

**프로젝트 소개**: "Redis Lua로 병목 원인을 찾고 해결한 뒤 재실험으로 검증, 데이터 기반으로 트레이드오프를 결정한 실험 중심 프로젝트"

**CI/CD**: "OIDC 기반 키 없는 AWS 인증, SSH 차단 + SSM Session Manager, Blue-Green 무중단 배포"

**한계 인지**: "단일 인스턴스 CPU 한계 → ASG 수평 확장, 데이터로 근거 제시"
