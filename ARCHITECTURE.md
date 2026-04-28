# 1Million Campaign Orchestration System — 아키텍처 문서

> 작성일: 2026-04-27  최종 업데이트: 2026-04-28
> v1(10만 트래픽) → v3 Redis-first(100만 트래픽) 전체 설계 및 구현 정리
> 현재 상태: Phase B(100만 테스트) 완료 ✅ — TPS ~2,442/s, 정합성 1,000,000건

---

## 목차

1. [프로젝트 개요](#1-프로젝트-개요)
2. [전체 데이터 흐름](#2-전체-데이터-흐름)
3. [API 계층 — 참여 요청 처리](#3-api-계층--참여-요청-처리)
4. [Redis 설계](#4-redis-설계)
5. [ParticipationBridge — Redis to Kafka](#5-participationbridge--redis-to-kafka)
6. [Kafka 구성](#6-kafka-구성)
7. [ParticipationEventConsumer — DB 최종 기록](#7-participationeventconsumer--db-최종-기록)
8. [모니터링 아키텍처](#8-모니터링-아키텍처)
9. [인프라 구성 (AWS + Terraform)](#9-인프라-구성-aws--terraform)
10. [CI/CD 파이프라인](#10-cicd-파이프라인)
11. [부하 테스트 결과 및 병목 개선 히스토리](#11-부하-테스트-결과-및-병목-개선-히스토리)
12. [설계 결정 근거](#12-설계-결정-근거)
13. [앞으로 할 작업 로드맵](#13-앞으로-할-작업-로드맵)

---

## 1. 프로젝트 개요

### 목표
선착순 캠페인 참여 시스템에서 **100만 트래픽**을 정합성 보장 하에 처리한다.

### 핵심 요구사항
- **공정성**: 먼저 요청한 사람이 먼저 당첨 (선착순)
- **정합성**: 재고 초과 발급 0건
- **가용성**: 단일 장애점(SPOF) 제거
- **성능**: 목표 TPS 1,000/s 이상

### 기술 스택
| 분류 | 기술 |
|------|------|
| 언어/프레임워크 | Java 21, Spring Boot (Virtual Thread) |
| 메시지 큐 | Apache Kafka (KRaft, 3-broker) |
| 캐시/큐 | Redis (ElastiCache CME 3샤드, Valkey 7.2) |
| DB | MySQL 8.0 (AWS RDS db.t3.micro) |
| 인프라 | AWS EC2, ALB, ElastiCache, RDS, CodeDeploy |
| IaC | Terraform |
| 모니터링 | Prometheus, Grafana, CloudWatch, Micrometer |
| 부하 테스트 | k6 |

---

## 2. 전체 데이터 흐름

```
[클라이언트]
    |
    | POST /api/campaigns/{id}/participation
    v
[ALB — alb-batch-kafka-api]
    |
    v
[Spring Boot App — batch-kafka-app EC2]
    |
    |-- 1. RateLimitService     SET NX EX 10  →  [Redis CME]
    |-- 2. RedisStockService    Lua DECR      →  [Redis CME]  (비활성/소진 즉시 컷)
    |-- 3. RedisQueueService    Lua LPUSH     →  [Redis CME]  queue:campaign:{id}
    |
    | 202 Accepted 반환 (DB 미접촉)
    |
    v
[ParticipationBridge — @Scheduled 100ms]
    |
    |-- SMEMBERS active:campaigns
    |-- RPOP queue:campaign:{id}  (동적 batchSize: 500/1000/2000)
    |
    v
[Kafka — campaign-participation-topic]
    | 파티션 10개, RF=3, min.ISR=2
    | 파티션 키: userId (균등 분산)
    |
    v
[ParticipationEventConsumer — concurrency=10]
    |
    |-- jdbcTemplate.batchUpdate() INSERT IGNORE (배치, DB 왕복 N→1)
    |-- (Redis 결과 캐시 제거 — CME pipeline 병목 원인이었음)
    |
    v
[MySQL RDS — batch-kafka-db]
```

### 핵심 설계 원칙

**선착순 번호 확정 시점 = Redis DECR 시점**
```
sequence = totalStock - remaining
```
- API 진입 시 Redis DECR 한 번으로 선착순 번호가 원자적으로 확정됨
- Kafka 순서와 무관 (파티션 여러 개여도 공정성 보장)
- DB INSERT는 Consumer가 비동기로 처리 → API 응답 경로에서 DB 완전 제거

---

## 3. API 계층 — 참여 요청 처리

### ParticipationService (핵심 진입점)

```java
public void participate(Long campaignId, Long userId) {
    // Step 1: 동일 유저 10초 내 재요청 차단
    if (!rateLimitService.isAllowed(campaignId, userId)) {
        throw new RateLimitExceededException(campaignId, userId);
    }

    // Step 2: 원자적 재고 감소 (Lua 스크립트)
    // EXISTS active:campaign:{id} → DECR stock → DEL flag(remaining==0) → GET total
    long[] stockResult = redisStockService.checkDecrTotal(campaignId);
    long remaining = stockResult[0];
    long total     = stockResult[1];

    // 비활성(-999) 또는 소진(<0) 즉시 컷 — DB 미접촉
    if (remaining == RedisStockService.INACTIVE_CAMPAIGN) {
        throw new StockExhaustedException(campaignId);
    }
    if (remaining < 0) {
        throw new StockExhaustedException(campaignId);
    }

    // remaining == 0: 마지막 재고 소진 → DB 캠페인 CLOSED 처리
    if (remaining == 0) {
        campaignRepository.closeAndResetStock(campaignId, CampaignStatus.CLOSED);
    }

    // Step 3: 선착순 번호 확정 (원자적)
    long sequence = total - remaining;

    // Step 4: Redis Queue에 적재 (Kafka 발행은 Bridge가 비동기 처리)
    String message = buildMessage(campaignId, userId, sequence);
    redisQueueService.push(campaignId, message);
    // 202 반환 — 여기서 응답 완료
}
```

**메시지 구조:**
```json
{ "campaignId": 13, "userId": 1042, "sequence": 5001 }
```

### RateLimitService — 동일 유저 중복 요청 차단

```java
public boolean isAllowed(Long campaignId, Long userId) {
    // 키: ratelimit:campaign:{id}:user:{userId}
    // SET NX EX 10 — 키 없으면 저장(통과), 키 있으면 무시(차단)
    String key = "ratelimit:campaign:" + campaignId + ":user:" + userId;
    Boolean result = redisTemplate.opsForValue()
            .setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
    return Boolean.TRUE.equals(result);
}
```

- 10초 TTL: 동일 유저가 10초 내 재요청 시 429 반환
- Boolean.TRUE.equals(): Redis 연결 오류 시 null 방어

### RedisQueueService — 큐 적재 (Lua 원자적)

```lua
-- push-queue.lua
-- LLEN 체크 후 100,000 초과 시 적재 거부
local size = redis.call('LLEN', KEYS[1])
if size >= tonumber(ARGV[1]) then
    return 0
end
redis.call('LPUSH', KEYS[1], ARGV[2])
return 1
```

- MAX_QUEUE_SIZE = 1,000,000 (100만 트래픽 기준, 데이터 유실 방지)
- LLEN + LPUSH 원자적 실행으로 race condition 없음
- Queue 상한 초과 시 push() false 반환 → DECR 이미 완료된 상태로 유실 위험 → 상한을 충분히 크게 유지

---

## 4. Redis 설계

### 키 구조

| 키 | 타입 | 역할 | TTL |
|----|------|------|-----|
| `ratelimit:campaign:{id}:user:{userId}` | String | 중복 요청 차단 플래그 | 10s |
| `active:campaigns` | Set | Bridge가 순회할 활성 캠페인 목록 | 영구 |
| `active:campaign:{id}` | String | Lua용 캠페인 활성 플래그 (해시태그) | 영구(재고 소진 시 DEL) |
| `stock:campaign:{id}` | String | 캠페인 잔여 재고 (DECR 대상) | 영구 |
| `total:campaign:{id}` | String | 캠페인 총 재고 (sequence 계산용) | 영구 |
| `queue:campaign:{id}` | List | API → Bridge 버퍼 큐 | 영구 |
| `participation:result:{userId}:{campaignId}` | String | Consumer 처리 결과 캐시 | 300s |

### Redis Cluster 해시태그 설계

ElastiCache CME(3샤드)에서 Lua 스크립트는 **동일 슬롯**의 키만 접근 가능합니다.
`{id}` 해시태그로 `active:campaign:{id}`, `stock:campaign:{id}`, `total:campaign:{id}` 세 키를 동일 슬롯에 배치합니다.

```
active:campaign:{13} → 해시태그 {13} → 슬롯 X
stock:campaign:{13}  → 해시태그 {13} → 슬롯 X  (동일)
total:campaign:{13}  → 해시태그 {13} → 슬롯 X  (동일)
```

### check-decr-total.lua (핵심 Lua 스크립트)

```lua
-- KEYS[1] = active:campaign:{id}   (캠페인 활성 플래그)
-- KEYS[2] = stock:campaign:{id}    (잔여 재고)
-- KEYS[3] = total:campaign:{id}    (총 재고)

if redis.call('EXISTS', KEYS[1]) == 0 then
    return {-999, 0}      -- 비활성 캠페인: 즉시 차단
end

local remaining = redis.call('DECR', KEYS[2])

if remaining == 0 then
    redis.call('DEL', KEYS[1])  -- 마지막 재고 소진 → active flag 제거
end

local total = tonumber(redis.call('GET', KEYS[3])) or 0
return {remaining, total}
```

**3가지 케이스:**
1. `remaining == -999`: 비활성 캠페인 → 즉시 400
2. `remaining < 0`: 재고 소진 후 추가 DECR → 보상 INCR 후 400
3. `remaining >= 0`: 정상 → `sequence = total - remaining` 확정 후 LPUSH

### 활성 캠페인 이중 관리 구조

```
active:campaigns (전역 Set)       active:campaign:{id} (캠페인별 플래그)
    ↑                                     ↑
    Bridge SMEMBERS 순회용               Lua DECR 가드용
    (Lua 밖에서만 접근)                   (해시태그 슬롯 통일)

캠페인 생성 시: SADD + SET 1
재고 소진 시:   Lua가 DEL (플래그만) → Bridge가 RPOP null 시 SREM (전역 Set)
```

---

## 5. ParticipationBridge — Redis to Kafka

Bridge는 API와 Kafka 사이의 **유량 조절 버퍼** 역할을 합니다.
API는 즉시 202를 반환하고, Bridge가 100ms마다 큐를 소비해 Kafka에 발행합니다.

```java
@Scheduled(fixedDelay = 100)  // 이전 실행 완료 후 100ms 대기
public void drainQueues() {
    drainTimer.record(() -> {
        // active:campaigns Set 순회
        Set<String> campaignIds = redisTemplate.opsForSet().members("active:campaigns");
        for (String campaignIdStr : campaignIds) {
            drainCampaignQueue(Long.parseLong(campaignIdStr));
        }
    });
}

private void drainCampaignQueue(Long campaignId) {
    String queueKey = "queue:campaign:" + campaignId;
    Long queueSize = redisTemplate.opsForList().size(queueKey);
    int batchSize = resolveBatchSize(queueSize);  // 동적 배치 크기

    for (int i = 0; i < batchSize; i++) {
        String message = redisTemplate.opsForList().rightPop(queueKey);  // RPOP
        if (message == null) {
            // 큐 비었고 active flag도 없으면 재고 소진 완료 → 전역 Set 정리
            if (!redisStockService.isActive(campaignId)) {
                redisStockService.deactivateCampaign(campaignId);
            }
            break;
        }
        publishWithRetry(campaignId, message);
    }
}
```

### 동적 batchSize

큐 적체량에 따라 드레인 속도를 자동 조절합니다:

| 큐 크기 | batchSize |
|---------|-----------|
| < 10,000 | 500 |
| < 100,000 | 1,000 |
| >= 100,000 | 2,000 |

### Kafka 발행 신뢰성

```java
private void publishWithRetry(Long campaignId, String message) {
    String partitionKey = extractUserIdKey(message, campaignId); // userId로 파티션 분산

    for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
        try {
            kafkaTemplate.send(TOPIC_NAME, partitionKey, message);
            return; // 성공
        } catch (Exception e) {
            long backoffMs = (long) Math.pow(2, attempt) * 100L; // 200ms → 400ms
            Thread.sleep(backoffMs);
        }
    }
    // MAX_RETRY(3회) 소진 → DLQ + Slack 알림
    sendToDlqWithSlack(campaignId, message, "MAX_RETRY_EXCEEDED");
}
```

**파티션 키 = userId**: campaignId로 키를 설정하면 단일 캠페인의 모든 메시지가 한 파티션으로 쏠림.
userId로 변경 시 Consumer 지연 1.25s → 200ms (-84%) 개선 확인 (6차 테스트).

---

## 6. Kafka 구성

### 클러스터 구성

```
kafka-1 (172.31.5.164,  public_2a)  ← Leader 역할 분산
kafka-2 (172.31.16.164, public_2b)
kafka-3 (172.31.32.164, public_2c)
```

- **KRaft 모드**: ZooKeeper 없음, 브로커가 직접 메타데이터 관리
- **cluster.id**: 9CA3K977Q5GdJDSdlKZVEw

### 토픽 설정

```
campaign-participation-topic
  PartitionCount: 10
  ReplicationFactor: 3
  min.insync.replicas: 2
```

- 파티션 10개 → Consumer 10개 병렬 처리
- RF=3, min.ISR=2 → 브로커 1대 장애 시에도 쓰기 가능

### KafkaConfig — 파티션 수 자동 감지

```java
// 앱 기동 시 실제 토픽 파티션 수를 AdminClient로 조회
// → concurrency 자동 설정 (코드 수정 없이 파티션 변경 가능)
private int getTopicPartitionCount(String topicName) {
    try (AdminClient adminClient = AdminClient.create(configs)) {
        TopicDescription desc = adminClient.describeTopics(...)
                .allTopicNames().get(5, SECONDS).get(topicName);
        return desc.partitions().size();  // 10 반환
    }
    // 조회 실패 시 기본값 1 사용
    return 1;
}

factory.setConcurrency(partitionCount);  // Consumer 스레드 = 파티션 수
```

### Producer 설정

```yaml
kafka:
  producer:
    buffer-memory: 536870912  # 512MB — 대량 메시지 버퍼
    batch-size: 131072        # 128KB — 배치 효율
    linger-ms: 5              # 5ms 대기 후 배치 전송
    acks: all                 # 모든 ISR 승인 대기
    enable-idempotence: true  # 멱등성 (중복 방지)
```

---

## 7. ParticipationEventConsumer — DB 최종 기록

v3에서 Consumer의 역할은 단순합니다: **Kafka 메시지를 받아 DB에 배치 INSERT SUCCESS**

```java
@KafkaListener(
    topics = "campaign-participation-topic",
    groupId = "campaign-participation-group",
    containerFactory = "kafkaListenerContainerFactory"
)
public void consumeParticipationEvent(
        List<ConsumerRecord<String, String>> records,
        Acknowledgment acknowledgment) {

    // 1. 메시지 파싱 (sequence 없으면 DLQ)
    List<ParticipationEvent> events = parseRecords(records);

    // 2. 배치 INSERT IGNORE (DB 왕복 N→1, rewriteBatchedStatements=true)
    List<Object[]> batchArgs = events.stream()
        .map(e -> new Object[]{e.getCampaignId(), e.getUserId(), e.getSequence()})
        .toList();
    jdbcTemplate.batchUpdate(
        "INSERT IGNORE INTO participation_history (campaign_id, user_id, sequence, status, created_at) VALUES (?, ?, ?, 'SUCCESS', NOW())",
        batchArgs
    );
    // 배치 실패 시 단건 폴백 + DLQ

    // 3. Kafka 오프셋 수동 커밋
    acknowledgment.acknowledge();
}
// Redis 결과 캐시(writeResultCache) 제거 — ElastiCache CME pipeline 병목 원인
// 제거 전: Kafka lag 9K 누적, Consumer 지연 200ms+
// 제거 후: lag 거의 0, Consumer 지연 7.5~15ms
```

### 멱등성 보장 메커니즘

```sql
-- INSERT IGNORE: UNIQUE 충돌 시 에러 없이 무시
INSERT IGNORE INTO participation_history
    (campaign_id, user_id, sequence, status, created_at)
VALUES (?, ?, ?, 'SUCCESS', NOW())
```

```sql
-- UNIQUE 제약 (V3 마이그레이션)
ALTER TABLE participation_history
    ADD UNIQUE KEY uq_campaign_user (campaign_id, user_id);
```

Kafka at-least-once 재전송으로 동일 메시지가 다시 와도 INSERT IGNORE가 조용히 무시합니다.

### 결과 캐시 (폴링 API 지원)

Consumer 처리 완료 후 Redis에 결과를 캐시합니다:
```
KEY: participation:result:{userId}:{campaignId}
VALUE: "SUCCESS"
TTL: 300s
```

클라이언트가 `GET /api/campaigns/{id}/participation/{userId}/result`로 폴링하면:
1. Redis 캐시 확인 → 있으면 즉시 반환
2. 없으면 DB fallback

---

## 8. 모니터링 아키텍처

### 전체 구성

```
[Spring Boot App :8080]          [kafka-1 :9092]      [ElastiCache :6379]
    /actuator/prometheus              |                      |
         |                    [kafka-exporter :9308]  [redis-exporter :9121]
         |                           |                      |
         +---------------------------+----------------------+
                                     |
                            [Prometheus :9090]
                                     |
                            [Grafana :3000]
                       (terraform-mcp EC2에서 실행)
```

### 커스텀 비즈니스 메트릭 4종

| 메트릭명 | 타입 | 설명 |
|---------|------|------|
| `bridge.drain.duration` | Timer | Bridge drainQueues() 전체 소요시간 |
| `bridge.messages.published{campaignId}` | Counter | 캠페인별 Kafka 발행 성공 건수 |
| `consumer.pending_to_success.latency` | Timer | API LPUSH ~ Consumer INSERT 지연 |
| `redis.queue.size{campaignId}` | Gauge | 캠페인별 Redis Queue 현재 적재량 |

### QueueMetricsScheduler — Gauge 설계

```java
// Gauge는 "현재 값"을 표현 — Queue 적재량에 최적
// Counter/Timer와 달리 감소도 표현 가능

@Scheduled(fixedDelay = 10_000)  // 10초 주기
public void collectQueueSizes() {
    for (String campaignIdStr : activeIds) {
        Long campaignId = Long.parseLong(campaignIdStr);
        long size = redisTemplate.opsForList().size("queue:campaign:" + campaignId);

        // 첫 등장 시만 Gauge 등록 (이후 Map 값만 갱신)
        queueSizes.computeIfAbsent(campaignId, id -> {
            Gauge.builder("redis.queue.size", queueSizes,
                          m -> m.getOrDefault(id, 0L).doubleValue())
                    .tag("campaignId", String.valueOf(id))
                    .register(meterRegistry);
            return 0L;
        });

        queueSizes.put(campaignId, size);  // Gauge가 다음 스크래핑에서 이 값 반환
    }
}
```

### Grafana 대시보드 패널 구성 (13개)

| 번호 | 패널 | 핵심 쿼리 |
|------|------|-----------|
| 1 | API TPS | `rate(http_server_requests_seconds_count[1m])` |
| 2 | API p95 응답시간 | `histogram_quantile(0.95, ...)` |
| 3 | API p99 응답시간 | `histogram_quantile(0.99, ...)` |
| 4 | API 에러율 (5xx) | `rate(http_server_requests_seconds_count{status=~"5.."}[1m])` |
| 5 | Bridge 드레인 속도 | `rate(bridge_messages_published_total[1m])` |
| 6 | Bridge 사이클 소요시간 | `bridge_drain_duration_seconds` |
| 7 | Redis Queue 적재량 | `redis_queue_size` |
| 8 | Consumer PENDING→SUCCESS 지연 | `consumer_pending_to_success_latency_seconds` |
| 9 | Kafka Consumer Group Lag | `kafka_consumergroup_lag` |
| 10 | Redis 메모리 사용량 | `redis_memory_used_bytes` |
| 11 | HikariCP 커넥션 풀 | `hikaricp_connections` (pending/active/idle/max) |
| 12 | HikariCP 활성율 | `hikaricp_connections_active / hikaricp_connections_max` |
| 13 | CPU 사용률 | `process_cpu_usage` |

### CloudWatch 지표 (AWS 측)

- RDS: CPUUtilization, DatabaseConnections, WriteIOPS, DiskQueueDepth
- RDS: Slow Query Log (long_query_time=0.5s, log_output=TABLE)
- EC2: CPU Credit Balance/Usage (t3 burst 모니터링)

---

## 9. 인프라 구성 (AWS + Terraform)

### AWS 리소스 전체 구성

```
VPC (172.31.0.0/16)
├── 퍼블릭 서브넷
│   ├── public_2a — kafka-1 (172.31.5.164),   terraform-mcp
│   ├── public_2b — kafka-2 (172.31.16.164)
│   ├── public_2c — kafka-3 (172.31.32.164)
│   └── public_2d
├── 프라이빗 서브넷
│   ├── private_app_2a (172.31.100.0/24) — ASG 인스턴스
│   └── private_app_2b (172.31.101.0/24) — ASG 인스턴스 (multi-AZ)
│
├── ASG (batch-kafka-app-asg, min=2, max=3)
│   ├── Launch Template: ami-01c64e7a84a57e681 (Docker+CodeDeploy)
│   └── Target Tracking: CPU 60% (scale-in 비활성)
│
├── ALB (alb-batch-kafka-api, internet-facing)
│   └── Target Group → batch-kafka-app:8080
│
├── ElastiCache CME (Valkey 7.2)
│   ├── 샤드 1: primary + replica
│   ├── 샤드 2: primary + replica
│   └── 샤드 3: primary + replica
│   (cache.t3.micro × 6, 총 6노드)
│
└── RDS (batch-kafka-db, MySQL 8.0.44, db.t3.micro)
```

### Terraform 파일 구성

| 파일 | 관리 리소스 |
|------|------------|
| `main.tf` | Provider, S3 backend, DynamoDB lock |
| `vpc.tf` | VPC, IGW, Subnets, Route Tables |
| `security_groups.tf` | ALB/App/RDS/Kafka/ElastiCache SG |
| `ec2.tf` | IAM Role/Profile/Policy, kafka-1/2/3, terraform-mcp |
| `asg.tf` | Launch Template, ASG, Target Tracking Scaling Policy |
| `codedeploy.tf` | CodeDeploy App/DG, Service Role (ASG 연결) |
| `rds.tf` | RDS 인스턴스, 파라미터 그룹 (slow query) |
| `elasticache.tf` | ElastiCache CME, SSM 자동 등록 |
| `alb.tf` | ALB, Target Group, HTTP Listener |
| `iam.tf` | GitHub Actions OIDC Role |
| `dynamodb.tf` | terraform-lock (State Lock) |

### ElastiCache CME Terraform

```hcl
resource "aws_elasticache_replication_group" "redis" {
  replication_group_id       = "batch-kafka-redis"
  engine                     = "valkey"
  engine_version             = "7.2"
  node_type                  = "cache.t3.micro"
  num_node_groups            = 3    # 샤드 3개
  replicas_per_node_group    = 1    # 샤드당 replica 1개
  parameter_group_name       = "default.valkey7.cluster.on"
  automatic_failover_enabled = true
}

# terraform apply 시 SSM 자동 등록
resource "aws_ssm_parameter" "redis_cluster_nodes" {
  name  = "/batch-kafka/prod/SPRING_DATA_REDIS_CLUSTER_NODES"
  type  = "SecureString"
  value = "${aws_elasticache_replication_group.redis.configuration_endpoint_address}:6379"
  lifecycle { ignore_changes = [value] }
}
```

### SSM Parameter Store 관리

앱이 기동할 때 `beforeInstall.sh`가 SSM에서 환경변수를 자동 주입합니다:

```bash
# /batch-kafka/prod/* 경로에서 환경변수 로드
SPRING_DATASOURCE_URL=$(aws ssm get-parameter ...)
SPRING_DATA_REDIS_CLUSTER_NODES=$(aws ssm get-parameter ...)
SPRING_KAFKA_BOOTSTRAP_SERVERS=$(aws ssm get-parameter ...)
SLACK_WEBHOOK_URL=$(aws ssm get-parameter ...)
```

SSH를 완전히 차단하고 SSM Session Manager로만 접속합니다. 민감 정보가 코드에 노출되지 않습니다.

---

## 10. CI/CD 파이프라인

```
[GitHub push to main]
    | (app/campaign-core/** 변경 시만 트리거)
    v
[GitHub Actions]
    |-- OIDC → AWS IAM Role 임시 자격증명 (키 없음)
    |-- ./gradlew build
    |-- docker build → ECR push
    |-- appspec.yml + scripts/ → S3 upload
    |-- CodeDeploy 배포 트리거
    v
[CodeDeploy Blue-Green]
    |-- beforeInstall.sh: SSM에서 환경변수 로드 → .env 파일 생성
    |-- applicationStart.sh: docker compose down → docker compose up
    v
[batch-kafka-app EC2]
    (무중단 배포 완료)
```

### 보안 포인트
- OIDC 기반 인증: AWS Access Key 없음 (깃허브 OIDC → IAM Role 임시 토큰)
- EC2 SSH 포트 차단: SSM Session Manager만 허용
- Secrets: SSM SecureString (KMS 암호화)
- ECR: Private Registry (퍼블릭 노출 없음)

---

## 11. 부하 테스트 결과 및 병목 개선 히스토리

### 테스트 인프라
- k6 실행 위치: **terraform-mcp EC2 (VPC 내부)** → 인터넷 레이턴시 제거
- 캠페인 생성: `POST /api/admin/campaigns` (매 테스트 신규 생성)
- executor: `shared-iterations` (정확한 요청 수 제어)

### 전체 결과 추이

| 차수 | 핵심 변경 | TPS | p95 | HikariCP pending | 정합성 |
|------|----------|-----|-----|-----------------|--------|
| 1차 | 기준선 (pool=10) | 246/s | 6.34s | ~980 | ✅ |
| 2차 | HikariCP pool=20 | 275/s | 5.71s | ~950 | ✅ |
| 3차 | pool=40, mcp k6 | 323/s | - | ~900 | ✅ |
| 4차 | **v3 Redis-first** | 526/s | 3.33s | **거의 0** | ✅ |
| 5차 | 파티션 3개 (campaignId 키) | 543/s | 4.39s | 거의 0 | ✅ |
| 6차 | 파티션 3개 (userId 키) | 550/s | 3.12s | 거의 0 | ✅ |
| 7차 | 3브로커 + 파티션 10개, 12만 | ~1,150/s | - | 거의 0 | ✅ |
| 8차 | 3브로커 + 파티션 10개, 50만 | ~1,220/s | 2.81s | 거의 0 | ✅ |
| 9차 | **ASG 2대**, 50만 | ~2,014/s | - | 거의 0 | ✅ |
| 10차 | **writeResultCache 제거 + 배치 INSERT**, 100만 | ~2,613/s | 2.22s | 거의 0 | ❌ 574,888 (Queue 오버플로우) |
| **11차** | **MAX_QUEUE_SIZE 1M**, 100만 | **~2,442/s** | **2.74s** | **거의 0** | **✅ 1,000,000** |

### 병목 분석 및 해결

#### 1~3차: HikariCP pool 튜닝 한계 확인

```
VU 1,000개 동시 INSERT 구조:
pool=20 → 20개 처리, 980개 대기 → pending 950
pool=40 → 40개 처리, 960개 대기 → pending 900
결론: pool을 아무리 늘려도 동시 INSERT 구조가 유지되면 근본 해결 불가
```

#### 4차: v3 Redis-first 전환 (구조적 해결)

**변경 전 (v2):**
```
API: DECR → DB PENDING INSERT → LPUSH → 202
```

**변경 후 (v3):**
```
API: DECR → LPUSH → 202  (DB 미접촉)
Consumer: Kafka 메시지 수신 → DB INSERT SUCCESS 직접
```

**결과:**
- HikariCP pending: ~950 → 거의 0 (완전 해소)
- TPS: 323/s → 526/s (+63%)
- RDS CPU: 26.5% → 정상 수준

#### 5~6차: Kafka 파티션 키 최적화

campaignId → userId 파티션 키 변경으로 Consumer 지연 1.25s → 200ms (-84%)

파티션 키가 campaignId면 단일 캠페인 트래픽이 한 파티션으로 집중됩니다.
userId로 변경하면 10개 파티션에 고르게 분산되어 Consumer 10개가 병렬 처리합니다.

#### 7~8차: 앱 CPU 병목 확인 (현재 상태)

3브로커 + 파티션 10개 적용 후에도 TPS 상한이 ~1,220/s에 수렴합니다.
Grafana 확인 결과 앱 CPU 80~90% 고착 — t3.small 단일 인스턴스 한계.

```
HikariCP pending: 거의 0 (DB 병목 아님)
RDS CPU: 9.44% (DB 서버 여유 충분)
앱 CPU: 80~90% (유일한 병목)
```

**다음 단계: ASG 수평 확장**

스케일업(t3.xlarge) 대신 ASG를 선택한 이유:
- vCPU 2→4개, 비용 4배 vs t3.small 2대 = vCPU 4개 + 비용 2배 + 가용성
- 단일 인스턴스 SPOF 그대로 vs ASG로 SPOF 제거
- 트래픽 변화에 따른 자동 조절 불가 vs Target Tracking으로 자동 스케일인/아웃

---

## 12. 설계 결정 근거

### 공정성: Redis DECR 시점에 선착순 확정

```
sequence = totalStock - remaining
```

Kafka 파티션 여러 개를 써도 공정성이 깨지지 않는 이유:
- 선착순 번호는 API 진입 시 DECR 시점에 원자적으로 확정됨
- Kafka는 단순 전달 수단 (순서 보장 불필요)
- Consumer 재정렬 버퍼 같은 복잡한 구조 불필요

### Redis List를 Queue로 사용 (Redis Stream 미사용)

Kafka가 이미 영속성/재처리/병렬화를 담당합니다.
Redis Stream까지 도입하면 복잡도만 증가하고 실익이 없습니다.
Redis List LPUSH/RPOP으로 충분한 버퍼링이 가능합니다.

### Consumer: INSERT 직접 (PENDING UPDATE 방식 제거)

v2: API에서 PENDING INSERT → Consumer가 SUCCESS UPDATE
v3: API에서 INSERT 없음 → Consumer가 SUCCESS INSERT 직접

v3가 나은 이유:
- API 경로에서 DB 완전 제거 → HikariCP pending 해소
- PENDING 레코드 없으니 Spring Batch 복구 로직 단순화
- UNIQUE INSERT IGNORE로 멱등성 충분 보장

### Kafka acks=all + min.ISR=2

```
acks=all: 모든 ISR 브로커가 메시지 저장 확인 후 응답
min.ISR=2: ISR 2개 이상일 때만 쓰기 허용
```

3브로커 중 1대 장애 시에도 ISR=2 유지 → 데이터 유실 없음.

### Spring Boot Virtual Thread 활용

Virtual Thread(Java 21)로 스레드 풀 고갈 없이 수천 개 동시 요청 처리.
1~3차 테스트에서 HikariCP 대기 시간이 30초에 달해도 서비스가 유지된 이유입니다.

---

*이 문서는 실험 → 측정 → 개선 사이클을 통해 데이터 기반으로 결정된 내용을 정리한 것입니다.*

---

## 13. 앞으로 할 작업 로드맵

### Phase A — Auto Scaling Group ✅ 완료 (2026-04-27)

**배경**: 7~8차 테스트에서 앱 CPU 80~90% 고착 확인. t3.small 단일 인스턴스 TPS 상한 ~1,220/s.
스케일업(xlarge) 대신 ASG 수평 확장 선택 — 비용 효율 + SPOF 제거 + 탄력성.

**완료된 작업:**
- AMI 생성 (ami-01c64e7a84a57e681 — Docker/CodeDeploy 포함)
- `infra/asg.tf` — Launch Template + ASG(min=2,max=3) + Target Tracking(CPU 60%, scale-in 비활성)
- `infra/codedeploy.tf` — CodeDeploy import 후 ASG 연결 IaC화
- `infra/vpc.tf` — private_app_2b 서브넷 추가 (multi-AZ)
- 기존 단일 EC2 제거 (ALB 중복 등록 방지)
- Prometheus EC2 Service Discovery 전환 (고정 IP → Name 태그 동적 감지, 2/2 UP)

**결과**: ASG 2대 운영 중, CodeDeploy 배포 시 2대 순차 배포 확인.

---

### Phase B — 9차 부하 테스트: 100만 트래픽 (다음 작업)

ASG 구성 완료 후 실행합니다.

**테스트 설정:**
```bash
# 캠페인 생성 (재고 1,000,000)
curl -X POST $BASE_URL/api/admin/campaigns \
  -H "Content-Type: application/json" \
  -d '{"name":"load-test-1M","totalStock":1000000,"startDate":"2026-04-27","endDate":"2026-12-31"}'

# 테스트 실행
CAMPAIGN_ID=<id> \
TOTAL_REQUESTS=1200000 \
MAX_VUS=3000 \
DURATION=2400 \
bash ~/1milion-campaign-orchestration-system/stress-test/run-test.sh prod
```

**검증 포인트:**
- TPS: 단일 인스턴스 대비 인스턴스 수에 비례해서 향상되는지 (수평 확장 효과)
- CPU: 인스턴스별 40% 수준 분산 여부
- 정합성: SUCCESS = 1,000,000 정확히 일치 (재고 초과 발급 0건)
- HikariCP pending: 거의 0 유지 (v3 효과 유지)
- Redis Queue: 테스트 종료 후 0 수렴

---

### Phase C — API 엔드포인트 정리 (v3 기준 재정비)

v1/v2 잔재 엔드포인트를 v3 아키텍처 기준으로 정리합니다.

**주요 작업:**

| 작업 | 내용 |
|------|------|
| 불필요한 컨트롤러 제거 | v1 LoadTestController, TestController, KafkaManagementController 등 정리 |
| 폴링 API 개선 | `GET /api/campaigns/{id}/participation/{userId}/result` — Redis 캐시 우선, DB fallback |
| 캠페인 상태 조회 API | 재고/상태 Redis 우선 조회 (DB 미접촉) |
| Admin API 정리 | 캠페인 생성 시 Redis 초기화 (stock, total, active flag) 흐름 명확화 |
| API 문서화 | Swagger/OpenAPI 또는 README에 엔드포인트 명세 정리 |

---

### Phase D — Spring Batch 안전망 구현

v3에서 PENDING 레코드가 없어졌지만 Redis 장애 시 LPUSH 실패 → 유실 가능성에 대한 안전망이 필요합니다.

**구현할 Job 2개:**

#### 1. 재고 정합성 검증 Job (새벽 3시)
```
ItemReader:    캠페인별 (Redis stock 잔량) vs (totalStock - DB SUCCESS 건수) 비교
ItemProcessor: 불일치 감지 시 Slack 알림 + 보정값 계산
ItemWriter:    Redis stock 보정 (필요 시)
```

#### 2. Redis Queue 유실 복구 Job (5분 주기)
```
문제 상황: DECR은 됐는데 LPUSH 실패 → DB에 기록 없음 + Redis Queue에도 없음
           sequence 번호는 할당됐으나 DB에 행 없는 상태

ItemReader:    sequence 기준으로 DB에 없는 번호 탐지
               (totalStock - Redis stock) - DB SUCCESS 건수 > 0 이면 유실 있음
ItemProcessor: Redis Queue 재발행 가능 여부 판단 (캠페인 활성 여부)
ItemWriter:    LPUSH 재적재 → Bridge → Kafka → Consumer INSERT
```

**기존 Batch (유지):**
- `aggregateParticipationJob` — 새벽 2시, participation_history → campaign_stats 통계 집계

---

### Phase E — MCP 서버 (AI 자율 운영)

`mcp-server/` 디렉터리에 Python/FastAPI 기반 AI 운영 서버를 구현합니다.

**목표**: Claude가 Prometheus/Grafana 지표를 보고 자율적으로 운영 판단 + Slack 승인 후 실행

**아키텍처:**
```
[Grafana Alert / Prometheus]
    |
    v
[MCP Server — FastAPI on terraform-mcp EC2]
    |-- Prometheus API 조회 (TPS, CPU, Queue 크기, Lag)
    |-- 이상 감지 로직 (임계치 판단)
    |-- Claude API 호출 → 원인 분석 + 조치 추천
    |
    v
[Slack Webhook — 승인 요청]
    |
    | (승인 시)
    v
[AWS SDK 실행]
    |-- ASG DesiredCapacity 조정 (스케일아웃/인)
    |-- CodeDeploy 재배포 트리거
    |-- Kafka 파티션 상태 확인
    |-- DynamoDB에 감사 로그 기록 (무엇을/왜/누가 승인)
```

**MCP Tool 목록 (예정):**
| Tool | 설명 |
|------|------|
| `get_metrics` | Prometheus에서 현재 TPS/CPU/Queue 조회 |
| `analyze_bottleneck` | Claude에게 병목 원인 분석 요청 |
| `scale_out` | ASG DesiredCapacity +1 (Slack 승인 필요) |
| `scale_in` | ASG DesiredCapacity -1 (Slack 승인 필요) |
| `redeploy` | CodeDeploy 재배포 트리거 |
| `get_audit_log` | DynamoDB 감사 로그 조회 |

**구현 스택:**
- Python 3.11 + FastAPI
- boto3 (AWS SDK)
- anthropic SDK (Claude API)
- prometheus_api_client

---

### 전체 로드맵 요약

```
[완료] v3 Redis-first 아키텍처 전환
[완료] Kafka 3-broker KRaft 클러스터
[완료] ElastiCache CME 3샤드
[완료] 8차 테스트 (50만, TPS ~1,220/s, 정합성 완벽)
[완료] Phase A — ASG Terraform (asg.tf, codedeploy.tf, min=2/max=3, CPU 60% Target Tracking)
[완료] Phase B — 100만 트래픽 테스트 ✅
              - 9차: ASG 2대, TPS ~2,014/s
              - writeResultCache 제거 (CME pipeline 병목)
              - Consumer jdbcTemplate.batchUpdate() + rewriteBatchedStatements=true
              - MAX_QUEUE_SIZE 500K → 1M (데이터 유실 방지)
              - terraform-mcp t3.large (k6 OOM 방지)
              - 11차 최종: TPS ~2,442/s, 정합성 1,000,000 ✅

[예정] Phase C — API 엔드포인트 v3 기준 정리
[예정] Phase D — Spring Batch 안전망 (재고 정합성 검증 + 유실 복구)
[예정] Phase E — MCP 서버 (AI 자율 운영)
```

**최종 목표**: 100만 트래픽 처리 ✅ + AI가 자율적으로 운영 판단하는 시스템
