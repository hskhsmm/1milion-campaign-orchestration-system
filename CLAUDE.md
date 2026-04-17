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
app/campaign-core/     ← Spring Boot 앱 (v1 코드 + Kafka 수동 관리 전환 완료)
infra/                 ← Terraform (Phase 1 진행 중 — v1 기존 리소스 import 완료)
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

### 최근 변경 (2026-03-27)
- **Kafka 수동 파티션 관리 전환**: `KafkaTopicService` 삭제, `KafkaAdmin` Bean 제거
  - 이유: 서버 기동 시 Kafka 브로커 연결 필수 → 로컬/테스트 환경 불편
  - 토픽 생성은 Kafka CLI로 수동 관리 (`kafka-topics.sh --create`)
  - `KafkaConfig.java`에서 `KafkaAdmin`, `NewTopic` 빈 제거
  - `LoadTestService`에서 `KafkaTopicService` 의존성 제거
- **모니터링 추가**: `micrometer-registry-prometheus` 의존성 추가 (build.gradle)
  - `/actuator/prometheus` 엔드포인트 노출 → Prometheus 수집 예정

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
  1. RateLimitService (SET NX EX 10, 10초 내 동일 요청 차단)
  2. Redis DECR → remaining < 0이면 보상 INCR + 즉시 400
  3. sequence = totalStock - remaining (선착순 번호 원자적 확정)
  4. DB PENDING INSERT → historyId 획득 (자리 선점, 지수 백오프 재시도 3회)
     - DuplicateKeyException + 같은 sequence → 기존 historyId 반환
     - DuplicateKeyException + 다른 sequence → 보상 INCR + 409
     - INSERT 실패(DB 장애) → 재시도, MAX_RETRY 소진 시 보상 INCR + 503
  5. Redis Queue LPUSH (queue:campaign:{id}) → 실패 시 Spring Batch 안전망
  6. 202 반환

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

## v2 구현 TODO (이슈 기준)

### #2 선행 — 공통 인터페이스 확정 ✅ 완료 (B파트에서 처리)
- [x] `ParticipationStatus.java` — PENDING 추가
- [x] `ParticipationHistory.java` — sequence 필드, PENDING 생성자 추가
- [x] `ParticipationEvent.java` — historyId 필드 추가
- [x] `db/migration/V2__add_sequence_to_participation_history.sql` — Flyway 마이그레이션
- Redis Queue 키 형식: `queue:campaign:{campaignId}` 확정

### #3 A파트 — API 진입 ~ Queue 적재 (leepg 담당) ✅ 완료 (2026-04-13, PR 올리기 전)

#### ✅ 완료
- [x] `RateLimitService` 신규 — SET NX EX 10, 키: `ratelimit:campaign:{id}:user:{userId}`
- [x] `RedisQueueService` 신규 — Lua LLEN+LPUSH 원자적 실행, MAX_QUEUE_SIZE=100,000
- [x] `scripts/push-queue.lua` 신규 — LLEN 체크 후 LPUSH, 100,000 초과 시 return 0
- [x] `RedisStockService` v2 전환 — Lua DECR 제거 → 단순 DECR + `incrementStock()` 보상 INCR 추가
- [x] `RedisConfig` 정리 — `decreaseStockScript` Bean 제거, `pushQueueScript` Bean 추가
- [x] `CampaignService` — 캠페인 생성 시 `SADD active:campaigns` 추가 (Bridge 인식용)
- [x] `ParticipationHistory` — PENDING 생성자 `sequence` 파라미터 추가
- [x] `V3__add_unique_constraint_campaign_user.sql` — (campaign_id, user_id) UNIQUE 제약 추가
- [x] `ErrorCode` 추가 — RATE_LIMIT_EXCEEDED(429), STOCK_EXHAUSTED(400), PARTICIPATION_SERVICE_UNAVAILABLE(503), DUPLICATE_PARTICIPATION(409)
- [x] 예외 클래스 4개 신규 — `RateLimitExceededException`, `StockExhaustedException`, `DuplicateParticipationException`, `ParticipationServiceUnavailableException`
- [x] `ParticipationService` 전면 재작성 — RateLimit → DECR → PENDING INSERT → LPUSH → 202
  - `@Transactional` 제거: `save()` 즉시 커밋 → LPUSH는 반드시 커밋 이후 실행 보장
  - DuplicateKeyException sequence 비교로 TTL 만료 재요청 vs 동일 요청 구분
  - INSERT 실패 시 exponential backoff 재시도 (200ms → 400ms, 최대 3회)
  - 보상 INCR 3경로 모두 처리 (재고 소진 / DuplicateKey 다른 sequence / MAX_RETRY 소진)
- [x] `ParticipationController` — 200 OK → 202 Accepted

### #4 B파트 — Queue 소비 ~ DB 최종 기록 (hskhsmm 담당) ✅ 코드 완료

> 설계 문서: `A파트_장애시나리오_설계_v4.pdf` (루트)
> 브랜치: `feature/phase2-part-a` (A파트 브랜치에 B파트 merge 완료)
> 현재 상태: A/B 전체 코드 완료 (2026-04-13), PR 올리기 전

#### ✅ 완료
- [x] `ParticipationStatus.java` — PENDING 추가
- [x] `ParticipationHistory.java` — sequence 필드, PENDING 생성자 추가
- [x] `ParticipationEvent.java` — historyId 필드 추가
- [x] `ParticipationHistoryRepository.java` — bulkUpdateSuccess(AND status=PENDING 멱등성), findByCampaignIdAndUserId 추가
- [x] `ParticipationEventConsumer.java` — v2 전면 재작성 + Redis 결과 캐시 + Slack 연동 + fallbackIndividual
- [x] `SlackNotificationService.java` — 신규 생성
- [x] `ParticipationBridge.java` — 신규 작성
  - @Scheduled(fixedDelay=100ms), active:campaigns 순회, RPOP, batchSize=500 (yml 오버라이드 가능)
  - MAX_RETRY 3회 + exponential backoff (200ms→400ms), DLQ+Slack
  - 파티션 키: String.valueOf(campaignId), LPUSH 재적재 금지
- [x] `PollingController.java` — 신규 작성
  - GET /api/campaigns/{campaignId}/participation/{userId}/result
  - Redis cache 우선 → DB fallback
- [x] `CampaignCoreApplication.java` — @EnableScheduling 추가
- [x] `application-prod.yml` — 3-broker 플레이스홀더, partitions=10, replication-factor=3, slack.webhook-url 추가
- [x] `application-local.yml` — slack.webhook-url 추가

#### 📌 배포 전 AWS 작업 (인프라 올릴 때)
- Slack Incoming Webhook URL 발급 → SSM `/batch-kafka/prod/SLACK_WEBHOOK_URL` 등록
- SSM에 `KAFKA_BROKER_1`, `KAFKA_BROKER_2`, `KAFKA_BROKER_3` 등록 (3-broker 구성 후)

### #5 Redis 클러스터링 — ElastiCache Cluster 모드 전환 (hskhsmm 담당, A/B 완성 후)
- [ ] `elasticache.tf` 수정 (단일 노드 → Cluster 모드 3샤드)
- [ ] `RedisConfig.java` 수정 (RedisClusterConfiguration 적용)
- [ ] `application-prod.yml` Redis Cluster 엔드포인트 반영

### #6 Kafka 3-broker (hskhsmm 담당, Redis 클러스터링 이후)
- [ ] EC2 2대 추가 (kafka-2, kafka-3) — `ec2.tf`
- [ ] `application-prod.yml` broker 설정 반영

### Spring Batch v2 PENDING 재처리 (A/B 머지 + 인프라 구성 후)
- [ ] ItemReader: 5분 초과 PENDING 조회
- [ ] ItemProcessor: Redis Queue 재발행 가능 여부 판단
- [ ] ItemWriter: 재발행 성공 → 유지, 실패 → FAIL UPDATE

### 모니터링 — #22~#25 (진행 중)

> 진행 순서: 로컬 인프라 구성 → 커스텀 메트릭 코드 → Grafana 대시보드 + k6 검증 → AWS 반영
> 작업 브랜치: `feature/monitoring`

#### #22 로컬 모니터링 인프라 ✅ 완료 (2026-04-15, leepg)
- [x] `docker-compose.yml` — kafka-exporter(:9308), redis-exporter(:9121), Prometheus(:9090), Grafana(:3000) 추가
  - kafka-exporter: 내부 리스너 `kafka:29092` 사용 (컨테이너 내부 통신이므로 외부 9092 아님)
  - 포트는 `.env` 변수로 분리, 모든 모니터링 서비스 `campaign-net` 네트워크 포함
- [x] `monitoring/prometheus.yml` — spring-boot(app:8080), kafka-exporter(9308), redis-exporter(9121) 3개 타겟
- [x] `monitoring/grafana/provisioning/datasources/prometheus.yml` — 기동 시 자동 프로비저닝, `editable: false`
- [x] `.env` — KAFKA_EXPORTER_PORT, REDIS_EXPORTER_PORT, PROMETHEUS_PORT, GRAFANA_PORT, GRAFANA_USER, GRAFANA_PASSWORD 추가
- 로컬 검증 완료: `localhost:9090/targets` 3개 모두 UP

#### #23 커스텀 비즈니스 메트릭 ✅ 완료 (2026-04-15, leepg)
- [x] `ParticipationBridge.java` — `bridge.drain.duration` (Timer, drainQueues() 전체 소요시간), `bridge.messages.published` (Counter, campaignId 태그)
  - Timer는 Spring Bean 아니므로 생성자 수동 작성 (`MeterRegistry` 주입 후 Timer 초기화)
- [x] `ParticipationEventConsumer.java` — `consumer.pending_to_success.latency` (Timer, 배치 내 가장 오래된 createdAt 기준)
- [x] `QueueMetricsScheduler.java` 신규 — `redis.queue.size` (Gauge, campaignId 태그, 10초 주기)
  - `ConcurrentHashMap<Long, Long>` + `computeIfAbsent`로 신규 캠페인 최초 발견 시에만 Gauge 등록, 이후 Map 값만 갱신
- 로컬 검증 완료: `/actuator/prometheus` 커스텀 메트릭 4종 수집 확인
  - `bridge_drain_duration_seconds` — count=5062, sum=18.77s
  - `bridge_messages_published_total{campaignId="1"}` — 1.0
  - `consumer_pending_to_success_latency_seconds` — count=1, sum=2.683s
  - `redis_queue_size{campaignId="1"}` — 0.0 (큐 소진 정상)

#### 📌 #22/#23 코드리뷰에서 발견된 알려진 이슈 → 모두 수정 완료 (2026-04-17)
- **[완료]** `spring.task.scheduling.pool.size: 2` — `application-local.yml` 추가
- **[완료]** `.env` `KAFKA_BOOTSTRAP_SERVERS=kafka:29092` — 이미 올바른 값으로 존재 확인
- **[잔존]** `consumer.pending_to_success.latency` Timer 배치 대표값 1회 기록 → 추후 개선 권장

#### #24 Grafana 대시보드 구성 + k6 부하 검증 ✅ 완료 (2026-04-17, hskhsmm)
- [x] 알려진 이슈 수정: 스케줄러 스레드 풀 설정 (`application-local.yml`)
- [x] `monitoring/grafana/dashboards/campaign.json` — 10개 패널 구성
- [x] `monitoring/grafana/provisioning/dashboards/dashboard.yml` — 자동 프로비저닝 설정
- [x] `monitoring/grafana/provisioning/datasources/prometheus.yml` — `uid: prometheus` 고정 (dashboard.json UID 매칭)
- [x] `docker-compose.yml` — JVM `MaxMetaspaceSize` 128m → 256m 상향 (OOM 방지)
- [x] `stress-test/k6-load-test.js` — `__ITER` → `exec.scenario.iterationInTest` 교체 (VU 간 userId 중복 방지)
- [x] k6 로컬 검증 완료 (VU=10, 300 요청, 100% 202 성공)
- [x] Grafana 실시간 수집 확인
  - API TPS, p95/p99 응답시간(85~130ms), 에러율 0 ✅
  - Bridge 드레인 속도, 사이클 소요시간(2~10ms) ✅
  - `redis_queue_size` 0 수렴 ✅ ← 핵심 완료 기준
- 트러블슈팅 기록:
  - Grafana datasource UID 불일치 → provisioning yml에 `uid: prometheus` 고정으로 해결
  - k6 `__ITER` per-VU 문제 → 전역 iterationInTest 사용으로 해결
  - JVM Metaspace OOM (좀비 현상) → MaxMetaspaceSize 256m으로 해결

#### #25 AWS 모니터링 인프라 반영 — terraform (hskhsmm 담당) 🔲 진행 예정 (내일)
- [ ] `security_groups.tf` — terraform-mcp-sg에 9090(Prometheus)/3000(Grafana) ingress 추가
- [ ] `security_groups.tf` — app-sg에 8080/9121(redis-exporter) from terraform-mcp-sg ingress 추가
- [ ] `security_groups.tf` — kafka-sg에 9092 from terraform-mcp-sg ingress 추가
- [ ] terraform-mcp EC2에 Prometheus + Grafana + kafka-exporter 배포 (SSM으로 설치 스크립트 실행)
- [ ] batch-kafka-app EC2에 redis-exporter 배포 (SSM)
- [ ] 완료 기준: `terraform-mcp:9090/targets` 3개 UP, Grafana 대시보드 데이터 표시
- 선행 조건: EC2 인스턴스(terraform-mcp, batch-kafka-app) 시작 필요 (비용 고려)


### Phase 1: Terraform (infra/ 전체 작성)

#### ✅ 완료된 작업 (2026-04-13 기준, terraform apply 완료)

| 파일 | 리소스 수 | 내용 |
|------|-----------|------|
| `main.tf` | - | provider, S3 backend + **DynamoDB lock** (terraform-lock) |
| `variables.tf` | - | region, account_id, vpc_id, subnet_id, github_repo |
| `vpc.tf` | 8개 | VPC(172.31.0.0/16), IGW, Route Table, 퍼블릭 서브넷 4개(2a~2d), private_app_2a |
| `security_groups.tf` | 5개 | alb-public(80/443), app-sg(8080), rds-mysql(3306), Kafka-SG(9092/9094), elasticache-redis(6379) |
| `ec2.tf` | 11개 | IAM Role×2, Instance Profile×2, Policy×3, Policy Attachment×6, EC2×3 (batch-kafka-app, kafka-1, terraform-mcp) |
| `rds.tf` | 1개 | batch-kafka-db (MySQL 8.0.44, db.t3.micro, SSM Parameter Store 비밀번호 연동) |
| `dynamodb.tf` | 1개 | terraform-lock (PAY_PER_REQUEST, Terraform state lock용) |
| `alb.tf` | 3개 | alb-batch-kafka-api, tg-api-8080, HTTP 리스너 — import 완료 |
| `elasticache.tf` | 1개 | batch-kafka 서브넷 그룹 import 완료, Redis 클러스터 주석처리 (모니터링 후 활성화 예정) |
| `iam.tf` | 2개 | GitHubActions S3/SSM 최소권한 커스텀 정책 추가 |

**핵심 결정 사항:**
- Route Table Association: 암시적 메인 라우팅 테이블 연결 유지 (명시적 association 코드 없음)
- RDS 비밀번호: SSM `/batch-kafka/prod/SPRING_DATASOURCE_PASSWORD` 에서 가져옴 + `lifecycle { ignore_changes = [password] }`
- kafka-1 EC2: `associate_public_ip_address = false` (public 서브넷이지만 IGW로 외부 통신, 퍼블릭 IP 불필요)
- security_groups description: 실제 AWS 값 그대로 사용 (forces replacement 방지)
- ALB Listener: `default_action` `ignore_changes` 추가 (stickiness 속성 Terraform provider 충돌 방지)
- RDS engine_version: AWS 자동 업그레이드로 8.0.43 → 8.0.44 반영
- GitHub Actions IAM: S3FullAccess → `campaign-deploy` 버킷 전용, SSMFullAccess → `/campaign/prod/*` 읽기 전용
- terraform-mcp EC2: public_2a 서브넷, 퍼블릭 IP 활성화 (MCP 서버 Phase 3 대비), 현재 중지 상태

#### 🔲 남은 작업
- [ ] Kafka 3-broker EC2 추가 (v2 목표) — `ec2.tf`
- [ ] ElastiCache Redis 클러스터 apply (모니터링 구성 후) — `elasticache.tf`

#### 📌 배포 전 AWS 작업 완료
- [x] SSM `/batch-kafka/prod/SLACK_WEBHOOK_URL` 등록 완료

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
| VPC | vpc-02bacd8c658dc632e (172.31.0.0/16) |
| ECR | campaign-core |
| S3 (배포) | campaign-deploy-631124976154 |
| S3 (tfstate) | campaign-terraform-state-631124976154 |
| DynamoDB (lock) | terraform-lock |
| CodeDeploy App | campaign-core-app |
| Deployment Group | campaign-prod-dg |
| IAM Role (CI/CD) | github-actions-campaign-deploy-role ✅ 신규 레포 연동 완료 |
| IAM Role (앱 EC2) | ec2-batchkafka-role |
| IAM Role (MCP EC2) | terraform-mcp-role |
| EC2 (앱) | batch-kafka-app (t3.small, private-app-subnet-1) |
| EC2 (Kafka) | kafka-1 (t3.small, public-2a) |
| EC2 (MCP) | terraform-mcp (t3.small, AL2023) |
| RDS | batch-kafka-db (MySQL 8.0.44, db.t3.micro, ap-northeast-2b) |
| ALB | alb-batch-kafka-api (internet-facing, 2a/2b, HTTP 80 → tg-api-8080) |
| ElastiCache | 서브넷 그룹 batch-kafka (존재), 클러스터 삭제 상태 (비용 절감) |
| SSM (앱) | /batch-kafka/prod/* |
| SSM (CI/CD) | /campaign/prod/ |

### OIDC 상태
Terraform으로 완료 — `repo:hskhsmm/1milion-campaign-orchestration-system:*` 적용됨

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
