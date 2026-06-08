# 1Million Campaign Orchestration System — 아키텍처 문서

> 최종 업데이트: 2026-05-08
> v1(DB Row Lock) → v3 Redis-first(150만 트래픽) 전체 설계 및 구현 정리
> 현재 상태: 14차 테스트 완료 ✅ — TPS ~3,737/s, 정합성 1,500,000건, 장애 테스트 T01~T07 전체 통과

---

## 목차

1. [프로젝트 개요](#1-프로젝트-개요)
2. [전체 데이터 흐름](#2-전체-데이터-흐름)
3. [API 계층 — 참여 요청 처리](#3-api-계층--참여-요청-처리)
4. [Redis 설계](#4-redis-설계)
5. [ParticipationBridge — Redis to Kafka](#5-participationbridge--redis-to-kafka)
6. [Kafka 구성](#6-kafka-구성)
7. [ParticipationEventConsumer — DB 최종 기록](#7-participationeventconsumer--db-최종-기록)
8. [Spring Batch 안전망](#8-spring-batch-안전망)
9. [MCP 서버 — AI 운영 레이어](#9-mcp-서버--ai-운영-레이어)
10. [모니터링 아키텍처](#10-모니터링-아키텍처)
11. [인프라 구성 (AWS + Terraform)](#11-인프라-구성-aws--terraform)
12. [CI/CD 파이프라인](#12-cicd-파이프라인)
13. [부하 테스트 결과 및 병목 개선 히스토리](#13-부하-테스트-결과-및-병목-개선-히스토리)
14. [장애 시나리오 테스트 T01~T07](#14-장애-시나리오-테스트-t01t07)
15. [설계 결정 근거](#15-설계-결정-근거)

---

## 1. 프로젝트 개요

### 목표

선착순 캠페인 참여 시스템에서 **150만 트래픽**을 정합성 보장 하에 처리한다.

### 핵심 요구사항

- **공정성**: 먼저 요청한 사람이 먼저 당첨 (선착순)
- **정합성**: 재고 초과 발급 0건, 데이터 유실 0건
- **가용성**: 단일 장애점(SPOF) 제거, 브로커/인스턴스 장애 자동 복구
- **성능**: 평균 TPS ~3,737/s, 피크 ~4,800/s (14차 기준)

### 기술 스택

| 분류 | 기술 |
|------|------|
| 언어/프레임워크 | Java 21, Spring Boot 3 (Virtual Thread) |
| 메시지 큐 | Apache Kafka (KRaft, 3-broker, 파티션 10개) |
| 캐시/큐 | Redis (ElastiCache CME 3샤드, Valkey 7.2) |
| DB | MySQL 8.0.44 (AWS RDS db.t3.micro) |
| 인프라 | AWS EC2, ALB, ASG, ElastiCache, RDS, CodeDeploy |
| IaC | Terraform (전체 인프라 코드화) |
| 모니터링 | Prometheus, Grafana, CloudWatch, Micrometer (커스텀 메트릭 4종) |
| AI 운영 | MCP Server (Python/FastAPI, Prometheus 폴링, Slack 알림) |
| 부하 테스트 | k6 (shared-iterations executor) |

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
[Spring Boot App — ASG batch-kafka-app-asg (t3.small × 2~3대)]
    |
    |-- 1. RateLimitService       SET NX EX 10  →  [Redis CME]  (10초 중복 차단)
    |-- 2. check-decr-enqueue.lua 단일 원자 실행 →  [Redis CME]
    |       ├── active 플래그 체크         (-999 → 400)
    |       ├── participated 키 체크        (-997 → 409)
    |       ├── Queue LLEN 체크             (-998 → 429, DECR 미실행)
    |       ├── DECR stock                 (< 0  → 400)
    |       ├── LPUSH queue:campaign:{id}  (큐 적재)
    |       └── SET participated           (영구 중복 방지)
    |
    | 202 Accepted 반환 (DB 미접촉)
    |
    v
[ParticipationBridge — @Scheduled fixedDelay=100ms]
    |
    |-- SMEMBERS active:campaigns
    |-- RPOP queue:campaign:{id}  (동적 batchSize: <10K→500 / <100K→1K / >=100K→2K)
    |-- publishAsync → KafkaTemplate (파티션 키: userId)
    |
    v
[Kafka — campaign-participation-topic]
    | 파티션 10개, RF=3, min.ISR=2
    | acks=all, enable.idempotence=true
    |
    v
[ParticipationEventConsumer — concurrency = 파티션 수 자동 감지]
    |
    |-- jdbcTemplate.batchUpdate() INSERT IGNORE  (배치, DB 왕복 N→1)
    |-- 배치 실패 시 단건 폴백 → DLQ
    |-- transient failure 시 ack 보류 → Kafka 자동 재전달
    |
    v
[MySQL RDS — batch-kafka-db]
```

### 핵심 설계 원칙

**선착순 번호 확정 시점 = Redis DECR 시점 (Lua 내부)**

```
sequence = totalStock - remaining
```

- API 진입 시 Lua 스크립트 실행 한 번으로 선착순 번호가 원자적으로 확정됨
- Kafka 순서와 무관 — sequence는 메시지에 포함되어 전달되므로 파티션 분산 후에도 공정성 보장
- DB INSERT는 Consumer가 비동기로 처리 → API 응답 경로에서 DB 완전 제거

---

## 3. API 계층 — 참여 요청 처리

### ParticipationService (현재 코드)

```java
public void participate(Long campaignId, Long userId) {
    // Step 1: 동일 유저 10초 내 재요청 차단
    if (!rateLimitService.isAllowed(campaignId, userId)) {
        throw new RateLimitExceededException(campaignId, userId);
    }

    // Step 2: 재고 차감 + 큐 적재 단일 Lua 원자 실행 — partial failure 원천 차단
    long[] result = redisStockService.checkDecrEnqueue(campaignId, userId);
    long remaining = result[0];
    long total     = result[1];

    if (remaining == RedisStockService.INACTIVE_CAMPAIGN) {  // -999
        throw new StockExhaustedException(campaignId);
    }
    if (remaining == RedisStockService.ALREADY_PARTICIPATED) {  // -997
        throw new DuplicateParticipationException(campaignId, userId);
    }
    if (remaining == RedisStockService.QUEUE_FULL) {  // -998
        rateLimitService.release(campaignId, userId);  // 즉시 재시도 허용
        throw new QueueFullException(campaignId);
    }
    if (remaining < 0) {
        throw new StockExhaustedException(campaignId);
    }

    // remaining == 0: 마지막 재고 소진 → DB 캠페인 CLOSED 처리
    if (remaining == 0) {
        try {
            campaignRepository.closeAndResetStock(campaignId, CampaignStatus.CLOSED);
        } catch (Exception e) {
            // Lua가 이미 active flag를 DEL했으므로 ConsistencyJob이 fallback 처리
            log.error("캠페인 DB 종료 실패. Redis는 이미 비활성화됨. campaignId={}", campaignId, e);
        }
    }

    long sequence = total - remaining;
    log.info("[ATOMIC] campaignId={} userId={} sequence={} remaining={}", ...);
    // 202 반환 — 여기서 응답 완료
}
```

**Queue Full 시 rate limit 해제 이유**: Queue Full은 사용자 잘못이 아닌 일시적 시스템 상태이므로,
`rateLimitService.release()`를 호출해 10초 대기 없이 즉시 재시도 가능하도록 설계.

**remaining==0 DB 호출 try-catch 이유**: T06 장애 테스트 중 발견된 허점.
RDS 장애 시 remaining==0 케이스에서 예외가 API로 전파되어 500이 발생했다.
Lua가 이미 active flag를 DEL했으므로 DB CLOSED 처리 실패해도 ConsistencyJob이 보완한다.

### RateLimitService

```java
// 키: ratelimit:campaign:{id}:user:{userId}
// SET NX EX 10 — 키 없으면 저장(통과), 키 있으면 무시(차단)
Boolean result = redisTemplate.opsForValue()
        .setIfAbsent(key, "1", TTL_SECONDS, TimeUnit.SECONDS);  // TTL_SECONDS = 10
return Boolean.TRUE.equals(result);  // null 방어
```

### HTTP 응답 코드 정리

| 응답 | 의미 | 원인 |
|------|------|------|
| 202 | 참여 성공 (큐 적재 완료) | 정상 |
| 400 | 재고 소진 또는 비활성 캠페인 | remaining < 0 또는 -999 |
| 409 | 이미 참여한 유저 | participated 키 존재(-997) |
| 429 | 중복 요청 차단 (10초 내) | RateLimitService / Queue Full(-998) |

---

## 4. Redis 설계

### 키 구조

| 키 | 타입 | 역할 | TTL |
|----|------|------|-----|
| `ratelimit:campaign:{id}:user:{userId}` | String | 10초 중복 요청 차단 | 10s |
| `active:campaigns` | Set | Bridge 순회용 활성 캠페인 목록 | 영구 |
| `active:campaign:{id}` | String | Lua용 캠페인 활성 플래그 | 영구 (재고 소진 시 DEL) |
| `stock:campaign:{id}` | String | 캠페인 잔여 재고 (DECR 대상) | 영구 |
| `total:campaign:{id}` | String | 캠페인 총 재고 (sequence 계산용) | 영구 |
| `queue:campaign:{id}` | List | API → Bridge 버퍼 큐 (MAX 1,500,000) | 영구 |
| `participated:campaign:{id}:user:{userId}` | String | 영구 중복 참여 방지 | 영구 |

> `participation:result:` 캐시 키는 제거됨 — ElastiCache CME pipeline에서 ConcurrentModificationException 유발, Redis-first 구조에서 불필요

### Lua 스크립트 상수

```java
// RedisStockService.java
private static final long MAX_QUEUE_SIZE    = 1_500_000;
public  static final Long INACTIVE_CAMPAIGN = -999L;  // 비활성 캠페인
public  static final Long QUEUE_FULL        = -998L;  // Queue 상한 초과 (DECR 미실행)
public  static final Long ALREADY_PARTICIPATED = -997L;  // 중복 참여
```

### Redis Cluster 해시태그 설계

ElastiCache CME(3샤드)에서 Lua 스크립트는 **동일 슬롯**의 키만 한 번에 접근 가능하다.
`{campaignId}` 해시태그로 캠페인 관련 5개 키를 모두 동일 슬롯에 배치한다.

```
active:campaign:{28}              ─┐
stock:campaign:{28}               ─┤
total:campaign:{28}               ─┼─→  슬롯 K  (해시태그 {28} 기준 계산)
queue:campaign:{28}               ─┤
participated:campaign:{28}:user:* ─┘
```

```java
// RedisStockService.java — 키 생성 방식
private static final String ACTIVE_FLAG_KEY_PREFIX  = "active:campaign:{";
private static final String STOCK_KEY_PREFIX        = "stock:campaign:{";
private static final String TOTAL_KEY_PREFIX        = "total:campaign:{";
private static final String QUEUE_KEY_PREFIX        = "queue:campaign:{";
private static final String PARTICIPATED_KEY_PREFIX = "participated:campaign:{";

private String getQueueKey(Long campaignId) {
    return QUEUE_KEY_PREFIX + campaignId + "}";  // "queue:campaign:{28}"
}
```

### check-decr-enqueue.lua (핵심 Lua 스크립트)

```lua
-- KEYS[1] = active:campaign:{campaignId}
-- KEYS[2] = stock:campaign:{campaignId}
-- KEYS[3] = total:campaign:{campaignId}
-- KEYS[4] = queue:campaign:{campaignId}
-- KEYS[5] = participated:campaign:{campaignId}:user:{userId}
-- ARGV[1] = maxQueueSize, ARGV[2] = campaignId, ARGV[3] = userId

-- 1. 캠페인 활성 여부 체크
if redis.call('EXISTS', KEYS[1]) == 0 then
    return {-999, 0}
end

-- 2. 중복 참여 체크 (TTL 만료 후 재시도도 차단)
if redis.call('EXISTS', KEYS[5]) == 1 then
    return {-997, 0}
end

-- 3. Queue 만원 체크 → 여기서 막히면 DECR 자체를 실행하지 않음 (핵심!)
if tonumber(redis.call('LLEN', KEYS[4])) >= tonumber(ARGV[1]) then
    return {-998, 0}   -- Partial Failure 원천 차단
end

-- 4. 재고 차감
local remaining = redis.call('DECR', KEYS[2])
if remaining < 0 then
    return {remaining, 0}
end

-- 5. 마지막 재고 소진 시 캠페인 active flag 제거
if remaining == 0 then
    redis.call('DEL', KEYS[1])
end

-- 6. sequence 계산 + 큐 적재 + 참여 이력 동시 기록
local total = tonumber(redis.call('GET', KEYS[3])) or 0
local sequence = total - remaining
local message = '{"campaignId":' .. ARGV[2] .. ',"userId":' .. ARGV[3] .. ',"sequence":' .. sequence .. '}'
redis.call('LPUSH', KEYS[4], message)
redis.call('SET', KEYS[5], '1')
return {remaining, total}
```

**12차 테스트에서 발견된 구조적 결함과 해결**: 기존에는 DECR(`check-decr-total.lua`)과 LPUSH(`push-queue.lua`)가 별도 Lua 스크립트로 두 번의 Redis 왕복이었다. Queue Full 시 DECR은 완료됐지만 LPUSH가 실패하는 Partial Failure가 발생해 141,062건이 유실됐다. 단일 Lua로 통합 후 Queue Full 체크를 DECR 앞에 배치해 원천 차단.

### 활성 캠페인 이중 관리

```
active:campaigns (전역 Set)           active:campaign:{id} (캠페인별 플래그)
    ↑                                         ↑
    Bridge SMEMBERS 순회용                   Lua DECR 가드용
    (Lua 밖에서만 접근 — 슬롯 무관)          (해시태그 슬롯 통일)

캠페인 생성 시: SADD + SET '1'
재고 소진 시:   Lua가 DEL (active flag만) → Bridge가 RPOP null 시 SREM (전역 Set)
```

---

## 5. ParticipationBridge — Redis to Kafka

Bridge는 API와 Kafka 사이의 **유량 조절 버퍼** 역할이다.
API는 즉시 202를 반환하고, Bridge가 100ms마다 큐를 소비해 Kafka에 비동기 발행한다.

```java
@Scheduled(fixedDelay = 100)  // 이전 실행 완료 후 100ms 대기
public void drainQueues() {
    drainTimer.record(() -> {
        Set<String> campaignIds = redisTemplate.opsForSet().members(ACTIVE_CAMPAIGNS_KEY);
        for (String campaignIdStr : campaignIds) {
            drainCampaignQueue(Long.parseLong(campaignIdStr));
        }
    });
}

private void drainCampaignQueue(Long campaignId) {
    String queueKey = "queue:campaign:{" + campaignId + "}";
    Long queueSize = redisTemplate.opsForList().size(queueKey);
    int dynamicBatchSize = resolveBatchSize(queueSize);

    for (int i = 0; i < dynamicBatchSize; i++) {
        String message = redisTemplate.opsForList().rightPop(queueKey);  // RPOP
        if (message == null) {
            if (!redisStockService.isActive(campaignId)) {
                redisStockService.deactivateCampaign(campaignId);
            }
            break;
        }
        publishAsync(campaignId, message);
    }

    // 롤링 배포 전환 기간: 구 키(해시태그 없음)에 고립된 메시지 드레인
    String legacyKey = "queue:campaign:" + campaignId;
    String legacyMessage;
    while ((legacyMessage = redisTemplate.opsForList().rightPop(legacyKey)) != null) {
        publishAsync(campaignId, legacyMessage);
    }
}
```

### 동적 batchSize

| 큐 크기 | batchSize |
|---------|-----------|
| < 10,000 | 500 |
| < 100,000 | 1,000 |
| >= 100,000 | 2,000 |

### Kafka 비동기 발행

```java
private void publishAsync(Long campaignId, String message) {
    String partitionKey = extractUserIdKey(message, campaignId);  // userId로 파티션 분산
    kafkaTemplate.send(TOPIC_NAME, partitionKey, message)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    // 발행 실패 → DLQ + Slack 알림
                    sendToDlqWithSlack(campaignId, partitionKey, message, "ASYNC_SEND_FAILED", ...);
                    return;
                }
                // 성공 → bridge.messages.published Counter 증가
                publishedCounters.get(campaignId).increment();
            });
}
```

**파티션 키 = userId**: campaignId로 설정 시 단일 캠페인 트래픽이 한 파티션으로 쏠린다.
userId로 변경 후 Consumer 지연 1.25s → 200ms (-84%) 개선 확인 (6차 테스트).

---

## 6. Kafka 구성

### 클러스터 구성

```
kafka-1 (172.31.5.164,  ap-northeast-2a)
kafka-2 (172.31.16.164, ap-northeast-2b)  ← Leader 역할 분산
kafka-3 (172.31.32.164, ap-northeast-2c)
```

- **KRaft 모드**: ZooKeeper 없음, 브로커가 직접 메타데이터 관리

### 토픽 설정

```
campaign-participation-topic
  PartitionCount:        10
  ReplicationFactor:     3
  min.insync.replicas:   2
```

- 파티션 10개 → Consumer 10개 병렬 처리
- RF=3, min.ISR=2 → 브로커 1대 장애 시에도 쓰기 가능 (T05에서 검증)

### KafkaConfig — 파티션 수 자동 감지

```java
// 앱 기동 시 AdminClient로 실제 토픽 파티션 수 조회
// → concurrency 자동 설정 (코드 수정 없이 파티션 변경 가능)
int partitionCount = getTopicPartitionCount(TOPIC_NAME);  // 10 반환
factory.setConcurrency(partitionCount);
factory.setBatchListener(true);
factory.getContainerProperties().setAckMode(AckMode.MANUAL);
```

### Producer 설정

```java
configProps.put(ProducerConfig.ACKS_CONFIG, "all");
configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
configProps.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
configProps.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 134_217_728);   // 128MB
configProps.put(ProducerConfig.BATCH_SIZE_CONFIG, 65_536);           // 64KB
configProps.put(ProducerConfig.LINGER_MS_CONFIG, 20);                // 20ms
```

---

## 7. ParticipationEventConsumer — DB 최종 기록

```java
@KafkaListener(topics = "campaign-participation-topic", groupId = "campaign-participation-group")
public void consumeParticipationEvent(
        List<ConsumerRecord<String, String>> records, Acknowledgment acknowledgment) {

    List<ParticipationEvent> events = parseRecords(records);  // poison pill → DLQ

    boolean hasTransientFailure = false;
    try {
        // 배치 INSERT — DB 왕복 N→1 (rewriteBatchedStatements=true 필수)
        List<Object[]> batchArgs = new ArrayList<>(events.size());
        for (ParticipationEvent event : events) {
            batchArgs.add(new Object[]{event.getCampaignId(), event.getUserId(), event.getSequence()});
        }
        jdbcTemplate.batchUpdate(
            "INSERT IGNORE INTO participation_history "
                    + "(campaign_id, user_id, sequence, status, created_at) "
                    + "VALUES (?, ?, ?, 'SUCCESS', NOW())",
            batchArgs
        );
    } catch (Exception batchException) {
        // 배치 실패 시 단건 폴백
        for (ParticipationEvent event : events) {
            try {
                participationHistoryRepository.insertSuccess(...);
            } catch (DataIntegrityViolationException e) {
                // UNIQUE 충돌 → INSERT IGNORE 효과, 정상 처리
            } catch (Exception e) {
                hasTransientFailure = true;
                sendToDlqWithSlack(...);  // 복구 불가 → DLQ
            }
        }
    }

    // 배치당 1줄 로그 (1건씩 로그 → 로그 I/O 병목 제거)
    log.info("Consumer batch processed. polled={}, parsed={}, success={}, transientFailure={}, latencyMs={}",
            records.size(), events.size(), successEvents.size(), hasTransientFailure, latencyMs);

    // transient failure(RDS 다운 등) 시 ack 보류 → Kafka 자동 재전달
    if (!hasTransientFailure) {
        acknowledgment.acknowledge();
    } else {
        log.warn("Skipping ack due to transient DB failure. Kafka will redeliver after recovery.");
    }
}
```

### 멱등성 보장

```sql
-- INSERT IGNORE: UNIQUE 충돌 시 에러 없이 무시
INSERT IGNORE INTO participation_history (campaign_id, user_id, sequence, status, created_at)
VALUES (?, ?, ?, 'SUCCESS', NOW())

-- UNIQUE 제약
ALTER TABLE participation_history ADD UNIQUE KEY uq_campaign_user (campaign_id, user_id);
```

Kafka at-least-once 재전송으로 동일 메시지가 다시 와도 INSERT IGNORE가 조용히 무시한다.

### rewriteBatchedStatements=true (JDBC URL 필수 옵션)

```
이 옵션 없으면: batchUpdate(100건) → 드라이버가 100번 개별 전송 → DB 왕복 100번
이 옵션 있으면: batchUpdate(100건) → INSERT INTO ... VALUES (...),(...),...  → DB 왕복 1번
```

코드에서 배치를 아무리 잘 짜도 드라이버 레벨에서 1건씩 풀어서 보낸다. SSM 파라미터에 명시적으로 추가했다.

---

## 8. Spring Batch 안전망

### PendingRecoveryJob

Redis에 stock이 있는데 DB success_count가 기대치보다 적으면 누락 감지 후 보정.

### ConsistencyRecoveryJob (MISSING_REDIS_STOCK)

Redis stock 키가 없는데 DB에 성공 기록이 있는 케이스 탐지.
T04에서 Redis stock 키를 강제 삭제한 뒤 `restoreStock=20` 복구 동작 확인 ✅

### DlqReplayJob

DLQ에 적재된 메시지를 분류 후 재처리.
- `MISSING_SEQUENCE` → 파싱 실패한 poison pill
- `INSERT_FAILED` → RDS 장애 등 일시적 오류 → 복구 후 재처리
- `ASYNC_SEND_FAILED` → Kafka 발행 실패 → Bridge 재발행

### AggregateParticipationJob

새벽 2시, `participation_history` → `campaign_stats` 통계 집계.

### BatchExecutionListener

모든 Job 실행 결과를 Slack으로 알림 전송.

---

## 9. MCP 서버 — AI 운영 레이어

`mcp-server/` — Python/FastAPI, terraform-mcp EC2에 Docker로 배포.

### 아키텍처

```
[Prometheus :9090]
    |  30초 폴링
    v
[MCP Server :8000]
    |-- P1/P2/P3 임계값 비교
    |-- 이상 감지 시 Slack Webhook 알림
    |
    v
[Slack — 운영팀 채널]
     (AI는 탐지+설명만, 실행은 사람이 판단)
```

### MCP 도구 7개

| Tool | 설명 |
|------|------|
| `get_monitor_status` | 현재 모든 지표 요약 (TPS/CPU/Queue/Lag) |
| `run_check` | 임계값 비교 후 이상 여부 판단 |
| `query_prometheus` | Prometheus PromQL 즉시 쿼리 |
| `query_prometheus_range` | 시계열 데이터 범위 쿼리 |
| `get_test_report` | 부하 테스트 결과 리포트 생성 |
| `reset_cooldown` | 알림 쿨다운 초기화 |
| `trigger_consistency_check` | 정합성 검증 Job 수동 트리거 |

### P1/P2/P3 임계값

| 레벨 | 조건 | 알림 |
|------|------|------|
| P1 | 5xx 발생 / Redis down | 즉시 |
| P2 | Consumer 지연 > 50ms / CPU > 90% | 즉시 |
| P3 | Kafka lag > 1,000 / Queue > 1M | 경고 |

**14차 테스트 실시간 검증**: Consumer 지연 212ms → P2 CRITICAL, CPU 92.6% → P2 CPU CRITICAL 알림 발생 ✅

---

## 10. 모니터링 아키텍처

### 전체 구성

```
[Spring Boot Apps :8080]         [Kafka brokers]        [ElastiCache]
  /actuator/prometheus                 |                      |
       |                      [kafka-exporter :9308]  [redis-exporter :9121]
       |                                |                      |
       +--------------------------------+----------------------+
                                        |
                               [Prometheus :9090]
                                        |
                               [Grafana :3000]
                          (terraform-mcp EC2, Docker monitoring 네트워크)
```

**Docker monitoring 네트워크**: 컨테이너 이름 기반 DNS (`redis-exporter:9121`, `kafka-exporter:9308`)
→ IP 변경 무관, prometheus.yml 수정 불필요.

**redis-exporter 위치**: terraform-mcp에 단독 배포 (앱 인스턴스에서 제거)
→ ASG 인스턴스가 몇 대든 Redis 메트릭은 항상 1개 시계열

### 커스텀 비즈니스 메트릭 4종

| 메트릭명 | 타입 | 설명 |
|---------|------|------|
| `bridge.drain.duration` | Timer | Bridge drainQueues() 전체 소요시간 |
| `bridge.messages.published{campaignId}` | Counter | 캠페인별 Kafka 발행 성공 건수 (드레인 속도 계산) |
| `consumer.pending_to_success.latency` | Timer | Consumer 배치 시작 ~ DB INSERT 완료 지연 |
| `redis.queue.size{campaignId}` | Gauge | 캠페인별 Redis Queue 현재 적재량 |

### Grafana 대시보드 13개 패널

| 번호 | 패널 | 핵심 쿼리 |
|------|------|-----------|
| 1 | API TPS | `rate(http_server_requests_seconds_count[1m])` |
| 2 | API p95 응답시간 | `histogram_quantile(0.95, ...)` |
| 3 | API p99 응답시간 | `histogram_quantile(0.99, ...)` |
| 4 | API 에러율 (5xx) | `rate(...{status=~"5.."}[1m])` |
| 5 | Bridge 드레인 속도 | `rate(bridge_messages_published_total[1m])` |
| 6 | Bridge 사이클 소요시간 | `bridge_drain_duration_seconds` |
| 7 | Redis Queue 적재량 | `redis_queue_size` |
| 8 | Consumer PENDING→SUCCESS 지연 | `consumer_pending_to_success_latency_seconds` |
| 9 | Kafka Consumer Group Lag | `kafka_consumergroup_lag` |
| 10 | Redis 메모리 사용량 | `redis_memory_used_bytes` |
| 11 | HikariCP 커넥션 풀 | `hikaricp_connections` (pending/active/idle/max) |
| 12 | HikariCP 활성율 | `hikaricp_connections_active / hikaricp_connections_max` |
| 13 | CPU 사용률 | `process_cpu_usage` |

---

## 11. 인프라 구성 (AWS + Terraform)

### AWS 리소스 전체 구성

```
VPC (172.31.0.0/16)
├── 퍼블릭 서브넷
│   ├── public_2a — kafka-1, terraform-mcp (Prometheus+Grafana+MCP)
│   ├── public_2b — kafka-2
│   └── public_2c — kafka-3
├── 프라이빗 서브넷
│   ├── private_app_2a (172.31.100.0/24) — ASG 인스턴스
│   └── private_app_2b (172.31.101.0/24) — ASG 인스턴스 (multi-AZ)
│
├── ASG (batch-kafka-app-asg)
│   ├── t3.small, min=2, max=3
│   ├── Launch Template: Docker + CodeDeploy AMI
│   ├── Target Tracking: CPU 60% (disable_scale_in=true)
│   └── lifecycle { ignore_changes = [desired_capacity] }
│
├── ALB (alb-batch-kafka-api, internet-facing)
│   └── Target Group → :8080, health_check_grace_period=180s
│
├── ElastiCache CME (Valkey 7.2, 3샤드)
│   ├── cache.t3.micro × 6 (샤드당 primary + replica)
│   └── automatic_failover_enabled = true
│
└── RDS (batch-kafka-db, MySQL 8.0.44, db.t3.micro, Multi-AZ 없음)
```

### Terraform 파일 구성

| 파일 | 관리 리소스 |
|------|------------|
| `main.tf` | Provider, S3 backend, DynamoDB lock |
| `vpc.tf` | VPC, IGW, Subnets, Route Tables |
| `security_groups.tf` | ALB/App/RDS/Kafka/ElastiCache/MCP SG |
| `ec2.tf` | IAM Role/Profile, kafka-1/2/3, terraform-mcp |
| `asg.tf` | Launch Template, ASG, Target Tracking Policy |
| `codedeploy.tf` | CodeDeploy App/DG (ASG 연결, import로 관리) |
| `rds.tf` | RDS, 파라미터 그룹 (slow query, binlog) |
| `elasticache.tf` | ElastiCache CME, SSM 자동 등록 |
| `alb.tf` | ALB, Target Group, HTTP Listener |
| `iam.tf` | GitHub Actions OIDC Role |
| `dynamodb.tf` | terraform-lock (State Lock) |

### ElastiCache destroy/apply 2단계 전략

비용 절감을 위해 ElastiCache를 테스트 시에만 띄울 때, 단순 `terraform apply` 시
ASG 인스턴스가 먼저 뜨고 Redis 연결 실패로 앱 기동이 실패한다.

```bash
# 1단계: ElastiCache 먼저 (15~20분 대기)
terraform apply -target=aws_elasticache_replication_group.redis

# 2단계: 나머지 전체
terraform apply
```

### SSM Parameter Store 관리

앱 기동 시 `beforeInstall.sh`가 SSM에서 환경변수 자동 주입:

```bash
SPRING_DATASOURCE_URL=$(aws ssm get-parameter --name /batch-kafka/prod/SPRING_DATASOURCE_URL ...)
SPRING_DATA_REDIS_CLUSTER_NODES=$(aws ssm get-parameter ...)
SPRING_KAFKA_BOOTSTRAP_SERVERS=$(aws ssm get-parameter ...)
SLACK_WEBHOOK_URL=$(aws ssm get-parameter ...)
```

**보안 원칙**: SSH 포트 차단, SSM Session Manager만 허용. AWS Access Key 없음 (OIDC). 민감 정보 코드 노출 없음.

---

## 12. CI/CD 파이프라인

```
[GitHub push to main + app/campaign-core/** 변경]
    v
[GitHub Actions]
    |-- OIDC → AWS IAM Role 임시 자격증명 (키 없음)
    |-- ./gradlew build
    |-- docker build → ECR push
    |-- appspec.yml + scripts/ → S3 upload
    |-- CodeDeploy 배포 트리거
    v
[CodeDeploy — OneAtATime]
    |-- beforeInstall.sh: SSM → .env 파일 생성
    |-- applicationStart.sh: docker compose down → up
    v
[ASG 인스턴스 순차 배포 완료]
```

---

## 13. 부하 테스트 결과 및 병목 개선 히스토리

### 테스트 인프라

- k6 실행 위치: **terraform-mcp EC2 (VPC 내부)** → 인터넷 레이턴시 제거
- executor: `shared-iterations` (정확한 요청 수 제어)

### 전체 결과 추이

| 차수 | 핵심 변경 | TPS | HikariCP pending | 정합성 |
|------|----------|-----|-----------------|--------|
| 1차 | v1 기준선 (DB Row Lock, pool=10) | 246/s | ~980 | ✅ |
| 2차 | HikariCP pool=20 | 275/s | ~950 | ✅ |
| 3차 | pool=40, terraform-mcp k6 | 323/s | ~900 | ✅ |
| 4차 | **v3 Redis-first 도입** | 526/s | **≈0** | ✅ |
| 5차 | 파티션 3개 (campaignId 키) | 543/s | ≈0 | ✅ |
| 6차 | 파티션 3개 (userId 키) | 550/s | ≈0 | ✅ |
| 7차 | 3-broker + 파티션 10개, 12만 | ~1,150/s | ≈0 | ✅ |
| 8차 | 3-broker + 파티션 10개, 50만 | ~1,220/s | ≈0 | ✅ |
| **9차** | **ASG 2대 수평 확장** | **~2,014/s** | ≈0 | ✅ (+65%) |
| 10차 | writeResultCache 제거 + 배치 INSERT | ~2,613/s | ≈0 | ❌ 574,888 (Queue 500K 오버플로우) |
| 11차 | MAX_QUEUE_SIZE 1M | ~2,442/s | ≈0 | **✅ 1,000,000** |
| 12차 | ASG 3대, 재고 130만 | ~2,507/s | ≈0 | ❌ **1,158,938 (141,062 유실)** |
| **13차** | **Lua 원자화 + MAX_QUEUE_SIZE 1.5M** | **~3,747/s** | ≈0 | **✅ 1,500,000** |
| **14차** | 동일 조건 재현 검증 (ASG 3/3/3) | **~3,737/s** | ≈0 | **✅ 1,500,000** |

### 12차 구조적 결함 발견 (141,062건 유실)

**원인 1**: MAX_QUEUE_SIZE=1M인데 130만 재고 → Queue overflow
**원인 2**: `check-decr-total.lua`(DECR) + `push-queue.lua`(LPUSH) 비원자성
→ Queue full 시 DECR 완료 후 LPUSH 실패 → 202 반환했지만 DB에 없음 → 복구 불가

**수정**: `check-decr-enqueue.lua` 단일 Lua 원자화 + MAX_QUEUE_SIZE 1.5M

### 병목 이동 경로

```
v1:  DB Row Lock → HikariCP pending 980 → TPS 246/s
v3:  Redis-first → pending ≈0            → TPS 526/s  (+114%)
     Kafka 3-broker + 파티션 10         → TPS 1,220/s (+132%)
     ASG 수평 확장 (2대)                → TPS 2,014/s (+65%)
     배치 INSERT + 1M Queue             → TPS 2,442/s + 100만 정합성
     Lua 원자화 + 1.5M Queue (3대)      → TPS 3,747/s + 150만 정합성

현재 병목: 앱 CPU 97% (t3.small 3대 한계)
  → 다음 스케일아웃 시 인스턴스 추가 또는 인스턴스 타입 변경
```

---

## 14. 장애 시나리오 테스트 T01~T07

| # | 시나리오 | 방법 | 결과 |
|---|----------|------|------|
| T01 | 중복 요청 차단 | 동일 userId 연속 요청 + TTL 만료 재시도 | ✅ 202→429→409 확인, Redis 이중 차감 버그 발견 및 수정 |
| T02 | 재고 소진 400 | 재고 2개 캠페인, 3개 요청 | ✅ diff=0, DB COUNT=2 정합성 완벽 |
| T03 | Queue 만원 429 | MAX_QUEUE_SIZE=500K 임시 축소 | ✅ Redis 잔여재고 + DB = total_stock 정합성 완벽 |
| T04 | ConsistencyJob 복구 | Redis stock 키 강제 삭제 | ✅ restoreStock=20 복구 확인 |
| T05 | Kafka 브로커 1대 장애 | kafka-2 stop | ✅ lag 스파이크 즉시 해소, 5xx 없음 |
| T06 | RDS 다운 → DLQ → 재처리 | SG 3306 삭제/복구 | ✅ hasTransientFailure ack 보류 → 복구 후 재처리, 구조적 허점 2건 발견 및 수정 |
| T07 | ASG 인스턴스 1대 terminate | 콘솔에서 강제 종료 | ✅ TPS 80% 급락 → 3~4분 완전 복구, 5xx 0건, diff=0 |

### T01 추가 발견 — 영구 중복 참여 차단 버그

RateLimitService TTL(10초) 만료 후 동일 userId 재요청 시 Redis stock 이중 차감 발견.
Lua에 `participated:campaign:{id}:user:{userId}` 영구 키 추가로 원천 차단.
DB UNIQUE 제약은 있었지만 재고 차감은 막지 못했던 구조적 허점이었다.

### T06 발견 — 구조적 허점 2건

1. `remaining==0` 시 동기 DB 호출이 API 경로에 남아있어 RDS 장애 시 500 전파 → try-catch로 수정
2. `actuator/health`가 DB 상태를 포함해 ALB TG가 unhealthy 처리 → Redis-first API인데도 5xx 발생 (개선 포인트로 기록)

---

## 15. 설계 결정 근거

### Shared-Nothing 아키텍처와 분산 환경 테스트

각 인스턴스가 메모리/디스크를 공유하지 않고 Redis/Kafka/DB를 통해서만 소통하는 구조.
수평 확장과 장애 격리에 유리하지만, **단위 테스트로 재현 불가능한 race condition**이 존재한다.

12차에서 인스턴스 3대가 동시에 Redis에 접근하자 11차까지 숨어있던 비원자성 버그가 터졌다.
로컬에서 아무리 테스트해도 실제 분산 환경에서 실제 부하를 줘봐야만 보이는 문제다.

### Lua 스크립트 원자화 — Partial Failure 차단

분산 환경에서 "체크 → 차감 → 적재"를 별도 명령으로 나누면 순간 타이밍에 따라 일부만 성공하는 Partial Failure가 발생한다. Redis의 Lua 스크립트는 단일 원자 명령으로 실행되므로 중간 실패가 없다.

Queue Full 체크를 DECR 앞에 배치한 것이 핵심 — DECR이 실행되지 않으므로 재고 차감 없이 차단된다.

### Redis List를 Queue로 사용 (Redis Stream 미사용)

Kafka가 이미 영속성/재처리/병렬화를 담당한다. Redis Stream까지 도입하면 복잡도만 증가하고 실익이 없다. Redis List LPUSH/RPOP으로 충분한 버퍼링이 가능하다.

### sequence = totalStock - remaining

Kafka 파티션 여러 개를 써도 공정성이 깨지지 않는 이유:
선착순 번호는 API 진입 시 DECR 시점에 원자적으로 확정된다. Kafka는 단순 전달 수단이므로 순서 보장이 불필요하다.

### Consumer: INSERT 직접 (PENDING UPDATE 방식 제거)

- v2: API에서 PENDING INSERT → Consumer가 SUCCESS UPDATE (DB 2회)
- v3: API에서 INSERT 없음 → Consumer가 SUCCESS INSERT 직접 (DB 1회, API 경로 DB 미접촉)

INSERT IGNORE + UNIQUE 제약으로 멱등성 보장. HikariCP pending 완전 해소.

### ASG 수평 확장 선택 (스케일업 미선택)

| 항목 | 스케일업 (t3.xlarge) | ASG (t3.small × 2) |
|------|---------------------|---------------------|
| vCPU | 4개 | 4개 |
| 비용 | 4배 | 2배 |
| 가용성 | 단일 SPOF | AZ 분산, 자동 복구 |
| 탄력성 | 없음 | CPU 60% 기준 자동 스케일 |

### Kafka acks=all + min.ISR=2

3브로커 중 1대 장애 시에도 ISR=2 유지 → 쓰기 가능, 데이터 유실 없음. T05에서 검증.

### Spring Boot Virtual Thread (Java 21)

스레드 풀 고갈 없이 수천 개 동시 요청 처리.
1~3차에서 HikariCP 대기 시간이 30초에 달해도 서비스가 유지된 이유.

---

*이 문서는 측정 → 원인 → 수정 → 재실험 사이클을 통해 데이터 기반으로 결정된 내용을 정리한 것입니다.*
*최종 상태: 150만 트래픽, TPS ~3,737/s, 정합성 diff=0, 5xx 0건, T01~T07 전체 통과*
