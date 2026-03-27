# 프로젝트 컨텍스트 — 1Million Campaign Orchestration System

> 이 파일은 Claude Code 세션 시작 시 자동으로 로드됩니다.
> 새 대화를 시작할 때 이 내용을 기반으로 바로 이어서 작업합니다.

---

## 프로젝트 개요

v1(`event-driven-batch-kafka-system`, 10만 트래픽)을 기반으로
v2(`1milion-campaign-orchestration-system`, 100만 트래픽 + AI 자율 운영)로 고도화하는 모노레포.

- **레포**: https://github.com/hskhsmm/1milion-campaign-orchestration-system
- **담당자**: 데브옵스 직무 취준생, 면접 준비 중
- **설계 문서**: `DESIGN_GUIDE_V2.md` (루트)

---

## 레포 구조

```
app/campaign-core/     ← Spring Boot 앱 (v1 코드 마이그레이션 완료)
infra/                 ← Terraform (현재 비어있음, Phase 1 작업 대상)
mcp-server/            ← Python/FastAPI AI 운영 서버 (Phase 3, 미작성)
stress-test/           ← k6 부하 테스트 스크립트
.github/workflows/     ← deploy.yml (루트에 있어야 GitHub Actions 인식)
```

---

## v1 현황 (app/campaign-core 현재 코드)

### 달성한 것
- Redis Lua DECR 원자적 즉시컷 (`stock:campaign:{campaignId}`)
- Kafka 파티션 1개 + concurrency=1 → violation_count=0 (완벽한 순서 보장)
- DB 원자적 UPDATE (`WHERE current_stock > 0`) 이중 안전망
- DLQ 토픽으로 Consumer 실패 격리
- Spring Batch 새벽 2시 통계 집계 (participation_history → campaign_stats)
- GitHub Actions → ECR → S3 → CodeDeploy Blue-Green CI/CD
- SSH 차단 + SSM Session Manager + Parameter Store

### 실험 결과 (핵심)
| 파티션 | DB TPS | switch_ratio | violation_count |
|--------|--------|--------------|-----------------|
| 1개    | 278    | 0            | 0               |
| 3개    | 505    | 0.0023       | 0               |
| 5개    | 595    | 0.0018       | 0               |

- Redis 도입 전: 파티션 늘려도 TPS ~285 수렴 → 원인: MySQL Row Lock 경합
- Redis 도입 후: 파티션 5개에서 2.1배 향상 확인
- 최종 선택: **파티션 1개** (공정성 우선)

### v1 한계
1. 단일 파티션 → 278 TPS 상한, 100만 트래픽 불가
2. Consumer에서 Redis DECR + DB 쓰기 → 핵심 흐름이 v2와 다름
3. Kafka 단일 브로커 SPOF
4. Redis 단일 인스턴스 SPOF
5. 인프라 수동 관리 (IaC 없음)
6. PENDING 선점 구조 없음 (장애 복구 기준점 없음)
7. RateLimitService 없음
8. Bridge/Queue 패턴 없음

---

## v2 핵심 설계 결정 (확정)

### 공정성 보장 방식 (가장 중요)
```
현재(v1): Kafka 단일 파티션으로 처리 순서 = 선착순
v2:       Redis DECR 시점에 선착순 확정 → Kafka 순서 무관
          sequence = totalStock - remaining (원자적 할당)
```

**왜 Consumer 재정렬(INCR + Reorder Buffer) 방식보다 나은가:**
- Head-of-line Blocking 없음
- Consumer 단순화 (재정렬 버퍼 불필요)
- 재고 소진 요청은 API 진입 시점 즉시 컷 (Kafka 진입 없음)
- Redis 키 하나로 sequence + stock 동시 처리

### v2 처리 흐름
```
POST /api/campaigns/{id}/participation
  1. RateLimitService (중복 요청 차단, SET NX EX 10)
  2. Redis Lua DECR → remaining < 0이면 즉시 400
  3. DB PENDING INSERT → historyId 획득 (자리 선점)
  4. Redis Queue LPUSH (queue:campaign:{id})
  5. 202 반환

ParticipationBridge (@Scheduled 100ms)
  → active:campaigns 순회 → RPOP batchSize개 → Kafka 발행

Kafka Consumer (10 파티션)
  → historyId PK로 PENDING 조회 → SUCCESS/FAIL UPDATE만
```

### Kafka 메시지 구조 (v2)
```json
{ "campaignId": 13, "userId": 1042, "historyId": 101868 }
```
- historyId 포함 → Consumer가 O(1) PK 조회
- sequence 불포함 → DECR 반환값(잔여재고)이 이미 순번

---

## v2 구현 TODO (우선순위 순)

### Phase 2-A: 핵심 흐름 재설계 (최우선)
- [ ] `ParticipationController` 전면 재설계 (DECR → PENDING → Queue → 202)
- [ ] `RedisQueueService` 신규 (LPUSH/RPOP, MAX_QUEUE_SIZE=100,000)
- [ ] `ParticipationBridge` 신규 (@Scheduled 100ms, Queue 드레인 → Kafka 발행)
- [ ] `ParticipationEventConsumer` 단순화 (historyId PK UPDATE만)
- [ ] Kafka 메시지 구조 변경 (historyId 추가)
- [ ] Kafka Producer 파티션 키 변경 (null → campaignId % partitionCount)

### Phase 2-B: 신규 컴포넌트
- [ ] `RateLimitService` 신규 (SET NX EX 10, 캠페인+유저 조합)
- [ ] `PendingRetryScheduler` 신규 (Spring Batch Chunk: 5분 초과 PENDING 재처리)
- [ ] `RedisSyncScheduler` 신규 (Redis stock vs DB 정합성 검증)

### Phase 2-C: DB 스키마
- [ ] `participation_history`에 `sequence BIGINT` 컬럼 추가

### Phase 2-D: 설정
- [ ] `application-prod.yml` Kafka 3-broker, partitions=10, replication-factor=3

### Phase 1: Terraform (infra/ 전체 작성)
- [ ] vpc.tf, ec2.tf (App + Kafka 3-broker EC2)
- [ ] rds.tf, elasticache.tf (Redis Cluster 3샤드)
- [ ] alb.tf, iam.tf (OIDC role), ssm.tf, variables.tf

### Phase 3: MCP 서버 (mcp-server/)
- [ ] Python/FastAPI MCP 서버
- [ ] AI 오케스트레이션 파이프라인
- [ ] Slack 승인 + DynamoDB 감사 로그

---

## 배치 현황 및 v2 방향

### 현재 배치 평가
- Spring Batch 인프라(Job/Step/Tasklet) 사용 중이나 실제 처리는 단일 GROUP BY 쿼리
- @Scheduled 대비 가치: 실행 이력(JobRepository), 재실행 API, 중복 방지
- Chunk processing 미활용 → Spring Batch 진가 발휘 못함

### v2에서 진짜 Spring Batch가 필요한 케이스
1. **PENDING 재처리** (ItemReader: 5분 초과 PENDING → ItemProcessor: 재발행 판단 → ItemWriter: 상태 UPDATE)
2. **Redis ↔ DB 정합성 검증** (캠페인별 stock 비교 → 불일치 알림)
3. **participation_history 대용량 아카이빙** (Chunk 단위 이동, Lock 방지)

---

## AWS 리소스 (현재 존재)

| 리소스 | 이름/값 |
|--------|---------|
| Account ID | 631124976154 |
| Region | ap-northeast-2 |
| ECR | campaign-core |
| S3 | campaign-deploy-631124976154 |
| CodeDeploy App | campaign-core-app |
| Deployment Group | campaign-prod-dg |
| IAM Role | github-actions-campaign-deploy-role |
| SSM prefix | /campaign/prod/ |

### OIDC Trust Policy 수정 필요
IAM → github-actions-campaign-deploy-role → Trust relationships에서
`repo:hskhsmm/1milion-campaign-orchestration-system:*` 로 업데이트 (레포 이동됨)

---

## CI/CD 현황

`.github/workflows/deploy.yml` (루트) — 정상 위치
- main 브랜치 push + `app/campaign-core/**` 변경 시 트리거
- OIDC → ECR → S3 → CodeDeploy 순서

---

## 기술 스택 (v2 목표)

| 분류 | v1 | v2 |
|------|----|----|
| Kafka | EC2 단일 브로커 | EC2 3-broker 클러스터 |
| Redis | ElastiCache 단일 | ElastiCache Cluster (3샤드) |
| 인프라 | 수동 콘솔 | Terraform IaC |
| 운영 | 없음 | Claude MCP 자율 운영 |
| 파티션 | 1개 | 10개 |

---

## 면접 핵심 멘트 (데브옵스 직무)

**프로젝트 소개:**
"Redis Lua로 병목 원인을 찾고 해결한 뒤 재실험으로 검증, 데이터 기반으로 트레이드오프를 결정한 실험 중심 프로젝트"

**CI/CD:**
"OIDC 기반 키 없는 AWS 인증, SSH 차단 + SSM Session Manager, Blue-Green 무중단 배포"

**한계 인지:**
"단일 파티션 278 TPS 상한 + 단일 브로커 SPOF → v2에서 Terraform + 3-broker + Redis Cluster로 해결"
