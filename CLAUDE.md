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
  4. RedisQueueService    LPUSH queue:campaign:{id}  (MAX_QUEUE_SIZE=500K)
  5. 202 반환 (DB 미접촉)

ParticipationBridge (@Scheduled 100ms)
  → SMEMBERS active:campaigns → RPOP → Kafka publish (userId 파티션 키)
  → 동적 batchSize: <10K→500 / <100K→1000 / >=100K→2000

Kafka Consumer (concurrency=10, 파티션 10개, RF=3, min.ISR=2)
  → jdbcTemplate.batchUpdate() INSERT IGNORE 배치 처리 (멱등성)
  → 배치 실패 시 단건 폴백 + DLQ
  → Redis 결과 캐시
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

### 9차 테스트 상세 (ASG 2대, 50만)
- 조건: TOTAL_REQUESTS=600,000, 재고=500,000, MAX_VUS=2,000, DURATION=1,200s
- TPS ~2,014/s (+65%) / avg 988ms (-39%) / 5xx: 0 ✅ / HikariCP pending 거의 0 ✅
- Redis Queue 100K 상한 도달 → MAX_QUEUE_SIZE 500K로 상향 완료
- **수평 확장 효과 확인: TPS 2배, avg 절반. 인스턴스당 CPU는 동일 수준 유지**

### 100만 테스트 시도 (9차 이후, 미완료)
- 조건: TOTAL_REQUESTS=1,200,000, 재고=1,000,000, MAX_VUS=2,000
- 78만 근처에서 k6 응답시간 폭발 → 테스트 중단
- **원인: Redis Queue 적체 (유입 속도 > Consumer 배출 속도)**
  - Consumer가 Kafka 메시지를 1건씩 DB INSERT → Consumer 처리 병목
  - Kafka lag 누적 → Bridge 배출 속도 제한 → Queue 500K 상한 도달 → 신규 요청 적재 실패
- **조치 (develop 브랜치, 배포 대기 중)**:
  - Consumer `jdbcTemplate.batchUpdate()` 배치 INSERT (DB 왕복 N→1)
  - JDBC URL `rewriteBatchedStatements=true` 추가 (SSM 업데이트 필요)
  - Consumer 건별 INFO 로그 제거 → 배치 단위 요약 로그로 교체

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

## 다음 작업 — Phase B: 100만 트래픽 테스트

**목표**: Consumer 배치 INSERT 적용 후 100만 트래픽 처리 검증.

### 배포 전 필수 작업
```bash
# SSM JDBC URL에 rewriteBatchedStatements=true 추가 (terraform-mcp에서)
CURRENT_URL=$(aws ssm get-parameter \
  --name /batch-kafka/prod/SPRING_DATASOURCE_URL \
  --with-decryption --query Parameter.Value --output text)

if [[ "$CURRENT_URL" == *"rewriteBatchedStatements=true"* ]]; then
  NEW_URL="$CURRENT_URL"
elif [[ "$CURRENT_URL" == *"?"* ]]; then
  NEW_URL="${CURRENT_URL}&rewriteBatchedStatements=true"
else
  NEW_URL="${CURRENT_URL}?rewriteBatchedStatements=true"
fi

aws ssm put-parameter \
  --name /batch-kafka/prod/SPRING_DATASOURCE_URL \
  --value "$NEW_URL" --type SecureString --overwrite
```

### 전체 로드맵
```
[완료] v3 Redis-first, Kafka 3-broker, ElastiCache CME, 8차 테스트 (50만 TPS ~1,220/s)
[완료] Phase A — ASG Terraform (asg.tf, codedeploy.tf, EC2 SD, min=2 운영 중)
[완료] 9차 테스트 (ASG 2대, 50만 TPS ~2,014/s)
[진행] Phase B — Consumer 배치 INSERT 적용 후 100만 테스트
       → 10만 검증 먼저 (records.size() 분포, Queue slope, Kafka lag 확인)
       → 통과 시 100만 테스트
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
OIDC → ECR → S3 → CodeDeploy (`CodeDeployDefault.OneAtATime`)

---

## 인프라 ON/OFF 순서

### 켤 때
```
1. RDS 시작 (3~5분 대기)
2. ASG desired=2, min=2, max=3
3. kafka-1/2/3 시작
4. terraform-mcp 시작
5. terraform-mcp SSM 접속 후 redis-exporter 실행:
   sudo docker run -d --name redis-exporter --restart unless-stopped -p 9121:9121 \
     -e REDIS_ADDR="$(aws ssm get-parameter --name /batch-kafka/prod/REDIS_EXPORTER_ADDR \
     --with-decryption --query Parameter.Value --output text)" \
     -e REDIS_EXPORTER_IS_CLUSTER=true oliver006/redis_exporter
```

### 끌 때
```
1. ASG desired=0, min=0, max=0
2. kafka-1/2/3 중지
3. terraform-mcp 중지
4. RDS 중지
```

> ElastiCache는 중지 기능 없음 — 장기 미사용 시 terraform destroy

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

**프로젝트 소개**: "Redis Lua로 병목 원인을 찾고 해결한 뒤 재실험으로 검증, 데이터 기반으로 트레이드오프를 결정한 실험 중심 프로젝트"

**CI/CD**: "OIDC 기반 키 없는 AWS 인증, SSH 차단 + SSM Session Manager, Blue-Green 무중단 배포"

**한계 인지**: "단일 인스턴스 CPU 한계 → ASG 수평 확장, 데이터로 근거 제시"

**Consumer 병목 개선**: "Kafka batch listener로 받아도 DB INSERT가 1건씩이면 소용없다는 걸 로그로 확인 후, JdbcTemplate.batchUpdate + rewriteBatchedStatements=true로 DB 왕복 N→1로 줄임"
