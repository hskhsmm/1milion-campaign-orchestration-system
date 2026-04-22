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
> PR #34 (feat/test-infra-setup), PR #35 (refactor/phase-part-A) → main 머지 완료 (2026-04-20)
> 현재 브랜치: refactor/phase-part-A

#### ✅ 완료
- [x] `ParticipationStatus.java` — PENDING 추가
- [x] `ParticipationHistory.java` — sequence 필드, PENDING 생성자 추가
- [x] `ParticipationEvent.java` — historyId 필드 추가
  - **버그 수정 (2026-04-19)**: `campaignId`, `userId` setter 추가 — Jackson 역직렬화 시 null 되어 `writeResultCache` 캐시 키 `participation:result:null:null` 오동작 수정
- [x] `ParticipationHistoryRepository.java` — bulkUpdateSuccess(AND status=PENDING 멱등성), findByCampaignIdAndUserId 추가
- [x] `ParticipationEventConsumer.java` — v2 전면 재작성 + Redis 결과 캐시 + Slack 연동 + fallbackIndividual
- [x] `SlackNotificationService.java` — 신규 생성
- [x] `ParticipationBridge.java` — 신규 작성
  - @Scheduled(fixedDelay=100ms), active:campaigns 순회, RPOP
  - **큐 크기 기반 동적 batchSize (2026-04-19)**: < 10,000 → 500 / < 100,000 → 1,000 / >= 100,000 → 2,000
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
- [x] `monitoring/grafana/dashboards/campaign.json` — 13개 패널 구성
  - 패널 1~10: API TPS/p95/p99/에러율, Bridge, Redis Queue, Consumer 지연, Kafka Lag, Redis 메모리 (기존)
  - 패널 11: HikariCP 커넥션 풀 4종 (pending/active/idle/max), pending 라인 빨간색 강조
  - 패널 12: HikariCP 활성율 (active/max), thresholds 0.7=yellow/0.95=red (color.mode: thresholds)
  - 패널 13: CPU 사용률, thresholds 0.7=yellow/0.85=red (color.mode: thresholds)
  - **수정**: 패널 9 Consumer Group명 `campaign-group` → `campaign-participation-group` (KafkaConfig.java 실제 값)
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

#### #24-추가 모니터링 코드 보완 ✅ 완료 (2026-04-20, leepg)
- [x] `ParticipationService.java` — `[TIMING]` 로그 추가 (`DB_INSERT`, `REDIS_PUSH`, `TOTAL` ms 단위)
  - DB_INSERT = HikariCP 대기 + SQL 실행 시간 합산 → 병목 진단 핵심 지표
- [x] `build.gradle` — `micrometer-registry-cloudwatch2` 의존성 추가
  - `application-prod.yml` cloudwatch.enabled: true인데 의존성 없으면 Spring Boot가 조용히 무시함
  - EC2 IAM `CloudWatchAgentServerPolicy`로 IAM 권한은 기존에 충족
- [x] `application-prod.yml` — `metrics` 액추에이터 엔드포인트 노출 추가 (CLI 디버깅용)
- [x] `application-prod.yml` — `spring.task.scheduling.pool.size: 2` 추가
  - 기본값 1 → Bridge(100ms)가 QueueMetricsScheduler(10s)를 기아시켜 `redis_queue_size` 지표 누락 방지
- [x] `application-local.yml` — 죽은 설정 `spring.kafka.consumer.group-id: campaign-group` 제거
  - KafkaConfig.java가 `campaign-participation-group` 하드코딩으로 덮어씌우므로 무의미
- [x] RDS slow query 파라미터 그룹 설정 (AWS 콘솔)
  - `mysql8.0` family 신규 파라미터 그룹 생성, `slow_query_log=1`, `long_query_time=0.5`, `log_output=TABLE`
  - RDS 인스턴스 파라미터 그룹 교체 + 재부팅 후 적용 확인

#### #25 AWS 모니터링 인프라 반영 — terraform (hskhsmm 담당) 🔄 진행 중 (2026-04-18)

##### ✅ 완료
- [x] `ec2.tf` — terraform-mcp-sg에 9090(Prometheus)/3000(Grafana) ingress 추가
- [x] `security_groups.tf` — app-sg에 8080/9121(redis-exporter) from terraform-mcp-sg ingress 추가
- [x] `security_groups.tf` — kafka-sg에 9092 from terraform-mcp-sg ingress 추가
- [x] `elasticache.tf` — Valkey 단일 노드 (`aws_elasticache_replication_group`) 생성 완료
  - engine: valkey 7.2, cache.t3.micro, replication_group_id: batch-kafka-redis
  - 엔드포인트: `batch-kafka-redis.3uttxb.ng.0001.apn2.cache.amazonaws.com`
  - SSM `/batch-kafka/prod/SPRING_DATA_REDIS_HOST` 등록 완료
- [x] `iam.tf` — GitHub Actions SSM 경로 `/campaign/prod` → `/batch-kafka/prod` 통일 + PutParameter 권한 추가
- [x] `beforeInstall.sh` — SSM 경로 `/batch-kafka/prod/*` 통일
- [x] `deploy.yml` — ECR_IMAGE SSM 경로 `/batch-kafka/prod/ECR_IMAGE` 통일
- [x] `docker-compose.prod.yml` — t3.small 기준 JVM 메모리 수정 (mem_limit 1500m, Xmx1g)
- [x] `application-prod.yml` — Kafka bootstrap-servers 환경변수명 SSM 키와 통일
- [x] EC2 3대 + RDS start 완료
- [x] terraform apply 완료 (ElastiCache Valkey, SG, IAM 반영)
- [x] terraform-mcp SSM 접속 → Docker 설치 완료
- [x] terraform-mcp — Prometheus + Grafana + kafka-exporter 컨테이너 실행 완료
  - prometheus.yml: spring-boot(172.31.100.157:8080), kafka-exporter(localhost:9308), redis-exporter(172.31.100.157:9121)
  - grafana datasource: prometheus uid 고정 (http://172.31.15.217:9090)
- [x] `ec2.tf` — batch-kafka-app, kafka-1 `associate_public_ip_address = true` 수정 + `private_ip` 고정
- [x] `ec2.tf` — EC2 3대 `lifecycle.ignore_changes = [associate_public_ip_address]` 추가 (stopped 상태 시 terraform plan drift 방지)
  - batch-kafka-app: `172.31.100.157`, kafka-1: `172.31.5.164`
  - 재생성 후에도 private IP 고정 → prometheus.yml 수정 불필요
- [x] terraform apply 완료 — batch-kafka-app, kafka-1 재생성 (퍼블릭 IP 할당)
- [x] kafka-1 SSM 접속 → Docker 설치 + Kafka 브로커 시작 + 토픽 2개 생성
- [x] batch-kafka-app SSM 접속 → Docker + CodeDeploy 에이전트 설치
- [x] `docker-compose.prod.yml` — redis-exporter 추가 (CI/CD 시 자동 실행, 포트 9121)
- [x] terraform-mcp — kafka-exporter 정상 연결 확인 (Up 상태)

##### CI/CD 트러블슈팅 기록 (2026-04-18)
- ECR 레포명 `campaign-core` → `batch-kafka-system` 수정
- S3 버킷명 `campaign-deploy-` → `batch-kafka-deploy-` 수정
- CodeDeploy 앱명 `campaign-core-app` → `batch-kafka-app`, 배포그룹 `campaign-prod-dg` → `batch-kafka-prod-dg` 수정
- `deploy.yml` paths에 `.github/workflows/**` 추가 (workflow 변경 시 CI/CD 트리거)
- `flyway-core` 의존성 누락 추가 (`flyway-mysql`만으로는 Flyway 미실행)
- DB 완전 초기화 후 `V1__init.sql` 없어서 `missing table [campaign]` 오류 → V1 마이그레이션 추가
- Spring Boot 4에서 `spring.batch.jdbc.initialize-schema` 제거됨 → `V4__batch_schema.sql` Flyway로 추가
- `iam.tf` S3 버킷 권한 `batch-kafka-deploy-` 로 수정 + terraform apply
- `baseline-version: 1` → `0` 수정 (V1 베이스라인으로 인식해서 V1__init.sql 건너뛰던 문제)
- `applicationStart.sh` — `docker-compose down` 먼저 실행 (포트 8080 충돌 방지)
- Flyway 로그 미출력 문제 → DB `show tables` 직접 확인으로 정상 실행 확인 (13개 테이블 생성 완료)

##### ✅ 완료 (2026-04-18)
- [x] Flyway 정상 실행 확인 — DB에 campaign, participation_history, BATCH_* 등 13개 테이블 생성 확인
- [x] CI/CD 배포 성공 → `/actuator/health` UP 확인
- [x] Prometheus targets 3개 UP — kafka-exporter 컨테이너 IP(172.17.0.3) 이슈 해결
  - prometheus.yml `localhost:9308` → `172.17.0.3:9308` 수정 (`/home/ec2-user/prometheus.yml` 마운트)
  - terraform-mcp 재시작 시 자동 반영 (`--restart unless-stopped` + 볼륨 마운트)
- [x] Grafana 대시보드 import 완료 (`campaign.json` 수동 import)
- [x] k6 ALB 부하 테스트 완료 → 1차 성능 측정 결과 확보

##### 📌 인프라 메모
- ElastiCache는 Valkey 사용 (Redis보다 약 20% 저렴, 클러스터링 전환 시도 유지)
- 퍼블릭 IP는 EC2 running 시 무료 (EIP 아님), stop 시 자동 반납
- 프라이빗 IP는 재시작해도 고정 → prometheus.yml 수정 불필요
- instance ID로 SSM 접속하므로 퍼블릭 IP 변경 무관
- 비용 절약: EC2/RDS 콘솔 stop, ElastiCache는 `terraform destroy -target=aws_elasticache_replication_group.redis`


### 로컬 통합 테스트 결과 (2026-04-19, A+B파트 머지 후)

#### 검증 항목 및 결과
| 항목 | 결과 |
|------|------|
| SUCCESS 100건 (totalStock=100, 200건 요청) | ✅ |
| sequence 중복 | 0건 ✅ |
| RateLimit 429 (동일 userId 10초 내 재요청) | ✅ |
| 재고 소진 400 | ✅ |
| result 캐시 키 (`participation:result:{userId}:{campaignId}`) | ✅ (setter 버그 수정으로 정상화) |

#### 수정된 버그
- `ParticipationEvent.java` — `campaignId`, `userId` setter 누락 → Jackson 역직렬화 시 null, writeResultCache 키가 `participation:result:null:null`로 고정되는 버그

#### Known Issue — 재고 소진 시 큐 잔류 (설계 허용 범위)
- 흐름: `remaining == 0` → Lua `SREM active:campaigns` 즉시 실행 → 큐에 잔류 메시지 Bridge 드레인 불가
- 안전망: Spring Batch `pendingRecoveryJob`이 5분 초과 PENDING 재처리 → 결국 SUCCESS
- 해결 시점: 1순위 코드 개선 "캠페인 자동 종료 + SISMEMBER" 항목에서 처리 예정

#### HikariCP prod 설정 수정 (2026-04-19)
- 원인: `SPRING_PROFILES_ACTIVE: prod`만 활성화 → `maximum-pool-size` 미설정 → HikariCP 기본값 **10** 적용
- 결과: AWS 1차 테스트 avg 3.94s / max 18.4s의 주요 원인 중 하나
- 수정: `application-prod.yml`에 `maximum-pool-size: 20`, `minimum-idle: 10` 추가
- p2/p3 프로필은 기존 설정(25/30) 유지 (파티션 증가 시 덮어씌움)

---

### 1차 부하 테스트 결과 (2026-04-18, AWS 환경)

#### 테스트 환경
- 앱: batch-kafka-app t3.small 단일 인스턴스
- DB: batch-kafka-db db.t3.micro (MySQL 8.0.44)
- Redis: ElastiCache Valkey 단일 노드
- Kafka: kafka-1 단일 브로커, 파티션 1개
- ALB: alb-batch-kafka-api

#### k6 설정
- TOTAL_REQUESTS: 15,000 (재고 10,000 초과 시나리오)
- MAX_VUS: 1,000
- DURATION: 60s

#### 결과

| 지표 | 값 |
|------|-----|
| TPS | **246/s** |
| avg latency | 3.94s |
| p95 | 6.34s |
| max | 18.4s |
| 성공 (202) | **9,990건** |
| 재고 초과 차단 (400) | 5,010건 |
| 재고 초과 발급 | **0건** ← 정합성 완벽 |

#### Grafana 확인 결과
- Bridge 드레인 속도: 피크 180 req/s, API TPS와 동일하게 움직임 → 정상
- Bridge 드레인 사이클: 피크 450ms → 감당 가능
- Redis Queue 피크: 15개 수준 → 거의 즉시 소비

#### 병목 분석
- **병목 위치**: HikariCP 커넥션 풀 고갈 (pool-size=10, 기본값) — INSERT SQL 자체는 빠름
- Bridge/Queue/Consumer는 병목 아님
- Virtual Thread(Spring Boot 4) 덕분에 스레드 풀 고갈 없이 대기 처리 → max 18.4s에도 시스템 유지
- HikariCP 기본 타임아웃 30s 이내 처리되어 타임아웃 에러 없음
- 이후 `application-prod.yml`에 `maximum-pool-size: 20` 추가 (2026-04-19)

#### 성능 개선 방향 (설계 철학: 공정성·정합성 우선)
- PENDING INSERT는 API 경로 유지 (장애 복구 기준점 — 빼면 Spring Batch 복구 불가)
- DB 스케일: RDS Proxy + Aurora 전환 (MySQL 대비 write TPS 최대 5배, 코드 변경 없음)
- Consumer 병렬화: Kafka 10파티션 + Consumer 10개 → SUCCESS UPDATE 선형 확장
- Redis: ElastiCache Cluster 3샤드 → TPS 향상 아님, 가용성(SPOF 제거) 목적

### 2차 부하 테스트 결과 (2026-04-20, AWS 파티션 1 — HikariCP 20 적용)

#### 테스트 환경 (1차와 동일)
- 앱: batch-kafka-app t3.small 단일 인스턴스
- DB: batch-kafka-db db.t3.micro (MySQL 8.0.44, gp3 IOPS 3,000)
- Redis: ElastiCache Valkey 단일 노드
- Kafka: kafka-1 단일 브로커, 파티션 1개
- ALB: alb-batch-kafka-api

#### k6 설정
- TOTAL_REQUESTS: 15,000 (재고 15,000 = 전량 202 시나리오)
- MAX_VUS: 1,000
- DURATION: 60s

#### 결과

| 지표 | 1차 (pool=10) | 2차 (pool=20) | 변화 |
|------|---------------|---------------|------|
| TPS | 246/s | **275/s** | +12% |
| avg latency | 3.94s | **3.54s** | -10% |
| p95 | 6.34s | **5.71s** | -10% |
| max | 18.4s | **15.4s** | -16% |
| 성공 (202) | 9,990건 | **15,000건** | 100% |
| 재고 초과 발급 | 0건 | **0건** | 정합성 유지 |

#### Grafana 확인 결과
- HikariCP pending 피크: **950** (pool=20임에도 여전히 포화)
- HikariCP active/max: **100%** 지속 → 풀이 여전히 부족
- RDS CPU: **7.27%** (DB 서버 측 여유 충분)
- WriteIOPS: **189/s** (gp3 3,000 IOPS 대비 6% 수준)
- DiskQueueDepth: **0.46** (임계치 아님)
- Slow query log: **비어있음** (0.5s 기준, SQL 자체는 빠름)

#### 확정 병목 분석 — HikariCP 커넥션 대기 (5가지 근거)

| 근거 | 데이터 | 결론 |
|------|--------|------|
| TIMING 로그 (tail, 테스트 말미 저VU) | DB_INSERT 12~23ms | SQL 자체는 빠름 |
| TIMING 로그 (head, 피크 고VU) | DB_INSERT 52~522ms | HikariCP 대기 포함 |
| Grafana HikariCP pending | 950 지속 | 커넥션 부족 확실 |
| CloudWatch RDS CPU | 7.27% | DB 서버 여유 충분 |
| Slow query log | 비어있음 | SQL 느린 것 아님 |

**결론**: 병목은 DB 서버나 SQL이 아니라 순수하게 HikariCP 커넥션 대기.
pool=20에도 1,000 VU 동시 요청 → INSERT 완료까지 점유 → 대부분 대기.
해결 방향: pool-size 증가 or DB 커넥션 사용 횟수 자체 감소 (totalStock Redis 캐싱으로 `findById` 제거).

### 3차 부하 테스트 결과 (2026-04-21, AWS 파티션 1 — HikariCP pool=40, terraform-mcp k6)

#### 테스트 환경
- 앱: batch-kafka-app t3.small 단일 인스턴스
- DB: batch-kafka-db db.t3.micro (MySQL 8.0.44, gp3 IOPS 3,000)
- Redis: ElastiCache Valkey 단일 노드
- Kafka: kafka-1 단일 브로커, 파티션 1개
- k6 실행 위치: **terraform-mcp EC2** (이전은 로컬 PC → 내부 네트워크로 변경, 측정 정확도 향상)

#### k6 설정
- TOTAL_REQUESTS: 15,000 (재고 10,000 초과 시나리오)
- MAX_VUS: 1,000
- DURATION: 60s (실제 완료: 46.4s)

#### 결과

| 지표 | 2차 (pool=20, 로컬 k6) | 3차 (pool=40, mcp k6) | 변화 |
|------|----------------------|----------------------|------|
| TPS | 275/s | **323/s** | +17% |
| avg latency | 3.54s | **3.01s** | -15% |
| max | 15.4s | **14.53s** | -6% |
| 성공 (202) | 15,000건 | **10,000건** | (재고 10K 기준 변경) |
| 재고 초과 발급 | 0건 | **0건** | 정합성 유지 ✅ |

> ⚠️ 2차는 재고=15,000 (전량 성공 시나리오), 3차는 재고=10,000 (5,000건 차단 시나리오) — 조건이 달라 직접 비교 시 참고

#### Grafana 확인 결과
- HikariCP pending 피크: **~900** (pool=20 때 950과 거의 동일 → pool 증가 효과 없음)
- HikariCP 활성율: **100%** 지속 포화
- 앱 CPU: **90%** (pool=40으로 동시 처리 증가 → 앱 CPU 부하 증가)
- RDS CPU: **23.9%** (이전 7.27% → 3배 증가, pool 증가로 동시 INSERT 늘어난 효과)
- DiskQueueDepth: **0.03** (이전 0.46 → 크게 개선, gp3 효과 확인)
- DatabaseConnections: **20.2** (pool=40이나 실제 동시 활성 커넥션은 20 수준)
- 5xx 에러율: **0** ✅
- Bridge 드레인 속도: 피크 45 req/s
- Consumer PENDING→SUCCESS 지연: 피크 35s

#### 결론 — pool 증가는 근본 해결책이 아님

```
VU 1000개 동시 요청 기준:
pool=20 → 20개 처리, ~980개 대기 → pending 950
pool=40 → 40개 처리, ~960개 대기 → pending 900
pool=100 → 100개 처리, ~900개 대기 → pending 여전히 높음
```

pool을 몇 배로 늘려도 VU 1,000개 동시 INSERT 구조가 유지되는 한 pending 근본 해결 불가.
**근본 해결: API 경로에서 DB INSERT 자체를 제거 → Redis-first 구조로 전환.**

#### k6 실행 위치 변경 (2026-04-21 확정)
- 이전: 로컬 PC → 인터넷 → AWS ALB (인터넷 레이턴시 포함, 측정 노이즈)
- 현재: **terraform-mcp EC2 → VPC 내부 → AWS ALB** (네트워크 노이즈 제거, 정확한 앱 성능 측정)
- terraform-mcp k6 설치: `sudo dnf install -y https://dl.k6.io/rpm/repo.rpm && sudo dnf install -y k6`
- 레포 clone: `git clone https://github.com/hskhsmm/1milion-campaign-orchestration-system.git`

---

### 로컬 검증 결과 (2026-04-22, v3 Redis-first)

#### 테스트 환경
- 로컬 Docker (MySQL, Redis, Kafka, 앱 단일 컨테이너)
- k6.exe 로컬 실행

#### 캠페인 1 (재고 1,000 / 요청 1,500 / VU 100)

| 지표 | 값 |
|------|-----|
| TPS | **807/s** |
| avg latency | **115ms** |
| p95 | **219ms** |
| 202 성공 | **1,000건** (재고 정확히 소진) ✅ |
| 400 초과 차단 | **500건** ✅ |
| 재고 초과 발급 | **0건** ✅ |

#### 캠페인 2 (재고 5,000 / 요청 7,000 / VU 200)

| 지표 | 값 |
|------|-----|
| TPS | **1,331/s** |
| avg latency | **147ms** |
| p95 | **234ms** |
| 202 성공 | **5,000건** (재고 정확히 소진) ✅ |
| 400 초과 차단 | **2,000건** ✅ |
| 재고 초과 발급 | **0건** ✅ |

#### Known Issue 재확인 — 재고 소진 시 active:campaigns SREM → 큐 잔류
- 재고 소진 시 `SREM active:campaigns` 즉시 실행 → Bridge 드레인 중단 → 큐 잔류
- `SADD active:campaigns {id}` 수동 복구 시 정상 드레인 → DB 최종 기록 완료 확인
- 해결: 다음 단계 인프라 작업 시 처리 예정

---

## 아키텍처 전환 결정 (2026-04-21 확정)

### 배경
3차 테스트까지 pool 튜닝 시도 → 효과 없음 데이터로 확인 → 구조적 해결로 방향 전환.
설계가 흔들리는 게 아니라 **실험 → 측정 → 개선** 사이클을 제대로 밟은 결과.

### 확정 아키텍처 (Redis-first)

```
POST /participate
  1. RateLimit     (Redis SET NX EX)
  2. DECR + LPUSH  (Redis Lua 원자적)
  3. 202 반환      ← DB 미접촉

Bridge (100ms) → RPOP → Kafka publish

Consumer (10파티션) → DB INSERT SUCCESS 직접
                    → UNIQUE 제약 멱등성 보장
                    → 실패 시 DLQ
```

### 각 선택의 근거

| 결정 | 이유 |
|------|------|
| API에서 DB 제거 | HikariCP pending 근본 해결, 업계 표준 (토스/카카오/쿠팡) |
| Redis List 유지 | Kafka 있으니 Redis Stream 중복, 복잡도만 증가 |
| Redis Stream 미사용 | Kafka가 영속/재처리/병렬화 역할 이미 담당 |
| Consumer INSERT | UNIQUE 제약으로 멱등성 충분 |
| Aurora는 선택 | 10파티션 Consumer 병렬화로 먼저 해결 시도, 부족 시 Aurora |

### 복구 전략 변경

| 항목 | 기존 | 변경 후 |
|------|------|---------|
| 복구 기준점 | DB PENDING 레코드 | Kafka at-least-once |
| Consumer 실패 | Spring Batch PENDING 재처리 | DLQ → Slack 알림 |
| 중복 방지 | DuplicateKeyException + sequence 비교 | UNIQUE INSERT |
| Redis 장애 유실 | Spring Batch 복구 | Bridge 100ms 이내 미처리분 (수십 건) |

### 수정 파일 (✅ 완료 — 2026-04-22, feature/redis-first-api, develop PR 예정)

| 파일 | 변경 내용 |
|------|-----------|
| `ParticipationEvent.java` | `historyId` → `sequence` (Redis DECR 시점 선착순 번호) |
| `ParticipationHistoryRepository.java` | `bulkUpdateSuccess`, `bulkUpdateFail` 제거 → `insertSuccess` (INSERT IGNORE) 추가 |
| `ParticipationService.java` | `insertPendingWithRetry` 제거, DECR → sequence 확정 → LPUSH → 202 반환 |
| `ParticipationEventConsumer.java` | `bulkUpdateSuccess` → `insertSuccess` 직접 호출, fallbackIndividual 제거 |
| `PendingRecoveryJobConfig.java` | `bulkUpdateFail` 제거, `historyId` → `sequence` (v3에서 PENDING 없음 → 배치 no-op) |

### 100만 트래픽 로드맵

```
이번:     API DB 제거           → HikariCP pending 해소, TPS 1,000/s+ 예상
다음:     10파티션 + 3브로커    → Consumer INSERT 10배 병렬화
그 다음:  Redis Cluster 3샤드   → SPOF 제거
선택:     Aurora                → Consumer INSERT 병목 시 적용
```

**이 3단계(코드 + 파티션 + 클러스터)면 100만 트래픽 달성 가능.**
Aurora는 Consumer DB INSERT가 병목으로 확인될 때 결정.

### 이슈 및 브랜치
- 이슈 초안: `issue_draft.md` (루트)
- 브랜치: `feature/redis-first-api` → develop PR 진행 중 (2026-04-22)

### 테스트 로드맵 (2026-04-22 기준)

> 상세 설계: `TEST_ROADMAP.md` (루트) 참고

| Phase | 핵심 변경 | 상태 |
|-------|-----------|------|
| 0 | 기준선 (TPS 246/s, p95 6.34s, pool=10) | ✅ 완료 |
| 1 | HikariCP prod 기본값 수정 (pool=20) + [TIMING] 로그 | ✅ 완료 (TPS 275/s, p95 5.71s) |
| 1-b | pool=40 테스트 | ✅ 완료 (TPS 323/s, pending ~900 — pool 효과 없음 확인) |
| 2 | **API DB 제거 (Redis-first 전환)** | ✅ 완료 — 로컬 검증 완료 (TPS 1,331/s, 정합성 완벽) |
| 3 | AWS 4차 테스트 — 파티션 1개 (v3 before/after 비교) | 🔄 다음 (develop PR → main 머지 → EC2 켜기) |
| 4 | AWS 5차 테스트 — 파티션 3개 (Consumer INSERT 병렬화 시작) | 대기 |
| 5 | Kafka 3브로커 + 파티션 10개 | 대기 (코드 변경 없음, 인프라만) |
| 6 | Redis Cluster 3샤드 | 대기 (elasticache.tf 수정) |
| 7 | Aurora *(Consumer INSERT 병목 시)* | 선택 |
| 8 | 백만 Spike 최종 검증 | 대기 |

#### k6 테스트 인프라 (2026-04-21 업데이트)
- `stress-test/k6-load-test.js` — BASE_URL env var 처리, thresholds 추가
- `stress-test/run-test.sh` — 환경별(local/prod) 실행 스크립트
  - terraform-mcp에서: `CAMPAIGN_ID=<id> TOTAL_REQUESTS=15000 MAX_VUS=1000 DURATION=60 bash stress-test/run-test.sh prod`
  - 파라미터 오버라이드: `CAMPAIGN_ID=2 TOTAL_REQUESTS=30000 ./run-test.sh prod`

#### 파티션별 yml (단일 브로커 한계 테스트용)
- `application-p2.yml`, `application-p3.yml`, `application-p5.yml`, `application-p10.yml`
- Consumer concurrency는 KafkaConfig가 Kafka 토픽 파티션 수 자동 감지 (yml 변경 불필요)
- yml은 HikariCP + Kafka Producer 튜닝용 (v1 기준으로 작성됨 → v2 테스트 후 조정 필요)
- 파티션 변경 방법: `kafka-topics.sh --alter --partitions N` → 앱 재시작

---

### 성능 개선 계획 (1차 테스트 기반)

> 상세 설계: `DESIGN_IMPROVEMENTS.md` 참고
> 설계 철학: 공정성·정합성 우선, 처리량은 안정적이면 충분

#### 1순위 — 코드 개선 (인프라 무관, 즉시 적용 가능)

| 항목 | 내용 | 효과 |
|------|------|------|
| 캠페인 자동 종료 + SISMEMBER | Lua로 SISMEMBER + DECR 원자화, remaining==0 시 DB CLOSED + active:campaigns 제거 | 재고 소진 후 Redis 낭비 제거, Bridge 빈 큐 순회 제거 |
| totalStock Redis 캐싱 | 캠페인 생성 시 `total:campaign:{id}` 저장, Lua 1번으로 SISMEMBER + DECR + GET 통합 | `findById()` DB 조회 완전 제거 → 커넥션 풀 INSERT 전용 확보 |

**기대 효과:**
```
DB 커넥션 사용: 25,000번 → 10,000번 (60% 감소)
avg latency: 3.94s → 2~3s
hikaricp_connections_pending 감소
```

**검증 순서:** 단위 테스트 → 통합 테스트 → k6 before/after 비교 → hikaricp 지표 확인

#### 2순위 — 인프라 + 코드 세트

| 항목 | 내용 | 선행 조건 |
|------|------|-----------|
| Redis Cluster + AOF | ElastiCache Cluster 3샤드, AOF 활성화 | elasticache.tf 수정 |
| Intent Key (의도 기록) | DECR + intent key Lua 원자화, 앱 크래시 시 double DECR 방지 | Redis Cluster + AOF 필수 |

#### 3순위 — Spring Batch 안전망

| 항목 | 내용 |
|------|------|
| 재고 복구 스케줄러 | Redis 키 없을 때 `total - SUCCESS - PENDING` 공식으로 자동 복구 |
| 5분 초과 PENDING 재처리 | ItemReader(PENDING 조회) → ItemProcessor(Queue 재발행) → ItemWriter(FAIL UPDATE) |

#### 인프라 개선 (DB 병목 근본 해결)

| 항목 | 효과 | 비고 |
|------|------|------|
| ~~EBS gp2 → **gp3** 변경~~ ✅ 완료 | IOPS 3,000 안정 보장 (버스트 없이) | 콘솔 + rds.tf 반영 완료 |
| RDS Proxy 추가 | 앱 2대 이상 시 커넥션 멀티플렉싱 | 앱 스케일아웃 시 필수 |
| RDS → **Aurora** 전환 | write TPS 최대 5배, 분산 스토리지 I/O | 코드 변경 없음, 엔드포인트만 교체 |

> ~~현재 db.t3.micro EBS gp2~~ → gp3 전환 완료 (IOPS 3,000 안정 보장)
> 코드 개선(커넥션 절약) + Aurora(INSERT 속도) 추가 개선 가능

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
- kafka-1 EC2: `associate_public_ip_address = true` + `private_ip = "172.31.5.164"` 고정 (SSM 접속 위해 퍼블릭 IP 필요)
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
