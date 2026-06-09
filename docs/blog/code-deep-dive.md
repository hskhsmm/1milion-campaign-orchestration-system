# 코드 심층 분석 — 면접 대비

> 이 문서는 1Million Campaign Orchestration System의 핵심 코드를 컴포넌트별로 분석한 면접 준비 자료입니다.
> 실제 코드를 기반으로 "왜 이렇게 구현했는가"를 설명할 수 있도록 정리했습니다.

---

## 목차

1. [전체 요청 흐름](#1-전체-요청-흐름)
2. [API 레이어 — Controller & 예외 처리](#2-api-레이어--controller--예외-처리)
3. [RateLimitService — 중복 요청 차단](#3-ratelimitservice--중복-요청-차단)
4. [Lua 원자화 — check-decr-enqueue.lua](#4-lua-원자화--check-decr-enqueueLua)
5. [RedisStockService — 키 설계와 해시태그](#5-redisstockservice--키-설계와-해시태그)
6. [ParticipationService — 흐름 오케스트레이션](#6-participationservice--흐름-오케스트레이션)
7. [ParticipationBridge — Redis → Kafka 드레인](#7-participationbridge--redis--kafka-드레인)
8. [KafkaConfig — Producer/Consumer 설정](#8-kafkaconfig--producerconsumer-설정)
9. [ParticipationEventConsumer — 배치 INSERT & ack 제어](#9-participationeventconsumer--배치-insert--ack-제어)
10. [ConsistencyRecoveryService — 이상 탐지 & 자동 복구](#10-consistencyrecoveryservice--이상-탐지--자동-복구)
11. [PendingRecoveryJob — PENDING 메시지 재발행](#11-pendingrecoveryjob--pending-메시지-재발행)
12. [StockRecoveryScheduler — Redis 재시작 복구](#12-stockrecoveryscheduler--redis-재시작-복구)
13. [면접 예상 질문 & 답변](#13-면접-예상-질문--답변)
14. [설정 파일 — application.yml & application-prod.yml](#14-설정-파일--applicationyml--application-prodyml)
15. [CI/CD 파이프라인 — GitHub Actions + CodeDeploy](#15-cicd-파이프라인--github-actions--codedeploy)
16. [Terraform 인프라 설계](#16-terraform-인프라-설계)
17. [커스텀 메트릭 & 모니터링 컴포넌트](#17-커스텀-메트릭--모니터링-컴포넌트)
18. [DlqReplayService — DLQ 재처리 정책](#18-dlqreplayservice--dlq-재처리-정책)
19. [보안 설계 — OIDC · SSM · Security Group](#19-보안-설계--oidc--ssm--security-group)
20. [MCP 서버 — AI 자율 운영 레이어](#20-mcp-서버--ai-자율-운영-레이어)

---

## 1. 전체 요청 흐름

```
POST /api/campaigns/{id}/participation
        │
        ▼
RateLimitService.isAllowed()       ← Redis SET NX EX 10 (10초 중복 차단)
        │ 통과
        ▼
RedisStockService.checkDecrEnqueue()  ← Lua 단일 원자 실행 (Redis 왕복 1회)
        │
        ├─ -999 → INACTIVE_CAMPAIGN → 400 StockExhaustedException
        ├─ -997 → ALREADY_PARTICIPATED → 409 DuplicateParticipationException
        ├─ -998 → QUEUE_FULL → rate limit 해제 후 429 QueueFullException
        ├─ < 0  → 재고 소진 → 400 StockExhaustedException
        └─ == 0 → 마지막 재고 → DB CLOSED (try-catch, 실패해도 Lua가 active DEL함)
        │ 성공 (remaining >= 1)
        ▼
      202 Accepted 반환 (DB 미접촉)

        ──────────────────────────────────────── 비동기 영역 ────────

ParticipationBridge (@Scheduled 100ms)
        │
        ├─ SMEMBERS active:campaigns
        ├─ RPOP queue:campaign:{id} (동적 batchSize: <10K→500 / <100K→1K / >=100K→2K)
        └─ KafkaTemplate.send(userId 파티션 키) → CompletableFuture
                │ 실패 시
                └─ DLQ Kafka Topic + DB 저장 + Slack 알림

Kafka Consumer (concurrency = 파티션 수 자동 감지, 현재 10)
        │
        ├─ List<ConsumerRecord> 배치 수신
        ├─ jdbcTemplate.batchUpdate() INSERT IGNORE
        ├─ 배치 실패 시 → 단건 폴백 → DLQ
        └─ transient failure 시 ack 보류 → Kafka 자동 재전달
```

---

## 2. API 레이어 — Controller & 예외 처리

### ParticipationController

```java
// ParticipationController.java:37
@PostMapping("/{campaignId}/participation")
public ResponseEntity<ApiResponse<Void>> participate(
        @PathVariable Long campaignId,
        @RequestBody @Valid ParticipationRequest request
) {
    participationService.participate(campaignId, request.getUserId());
    return ResponseEntity.accepted().body(
            ApiResponse.success("참여 요청이 접수되었습니다.")
    );
}
```

**핵심 포인트**
- `ResponseEntity.accepted()` → HTTP 202. DB에 쓰기 전에 응답하는 비동기 설계임을 명시
- 컨트롤러는 서비스에 위임만 하고 비즈니스 로직 없음
- `@Valid` → DTO 레벨 검증 (userId null 등) 을 Bean Validation에서 처리

**참고 — 동기 방식 엔드포인트가 왜 남아있는가**

`/participation-sync` 엔드포인트는 v1 구조(DB Row Lock 방식)를 그대로 유지해 성능 비교 기준으로 사용했습니다.
실제 운영에서 호출하면 HikariCP pending이 폭발합니다. "이걸 개선한 게 v3" 설명에 유용합니다.

---

### GlobalExceptionHandler

```java
// GlobalExceptionHandler.java:27
@ExceptionHandler(BusinessException.class)
public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
    return ResponseEntity.status(e.getHttpStatus())
            .body(ApiResponse.fail(e.getCode(), e.getMessage()));
}

@ExceptionHandler(InfrastructureException.class)
public ResponseEntity<ApiResponse<Void>> handleInfrastructureException(InfrastructureException e) {
    if (e.requiresAlert()) {
        // 운영자 알림 (Slack 등)
    }
    return ResponseEntity.status(e.getHttpStatus())
            .body(ApiResponse.fail(e.getCode(), "서버 오류가 발생했습니다."));
}
```

**예외 계층 설계**

```
Exception
└── BusinessException (4xx)         ← 클라이언트 오류, 에러코드 포함
    ├── RateLimitExceededException   → 429
    ├── DuplicateParticipationException → 409
    ├── QueueFullException           → 429
    ├── StockExhaustedException      → 400
    └── CampaignNotFoundException    → 404

└── InfrastructureException (5xx)   ← 서버 오류, requiresAlert() 여부 포함
    ├── KafkaPublishException
    └── ParticipationServiceUnavailableException
```

**면접 포인트**
- BusinessException과 InfrastructureException을 분리한 이유: 클라이언트 잘못(4xx)과 서버 잘못(5xx)을 코드 레벨에서 명확히 구분해 모니터링 알림 트리거 기준을 다르게 가져갑니다.
- `@RestControllerAdvice`로 전역 처리 → 컨트롤러에 try-catch 없음

---

## 3. RateLimitService — 중복 요청 차단

```java
// RateLimitService.java:28
public boolean isAllowed(Long campaignId, Long userId) {
    String key = KEY_PREFIX + campaignId + ":user:" + userId;
    // "ratelimit:campaign:1:user:42"

    Boolean result = redisTemplate.opsForValue()
            .setIfAbsent(key, "1", TTL_SECONDS, TimeUnit.SECONDS);
    // SET ratelimit:campaign:1:user:42 1 NX EX 10

    return Boolean.TRUE.equals(result);
}

public void release(Long campaignId, Long userId) {
    redisTemplate.delete(key);
}
```

**동작 원리**

| 상황 | Redis 동작 | 반환값 |
|------|-----------|--------|
| 키 없음 (첫 요청) | SET NX 성공 → 키 생성, TTL 10초 설정 | `true` → 통과 |
| 키 있음 (10초 내 재요청) | SET NX 실패 → 아무것도 안 함 | `false` → 429 |

**`release()`가 필요한 이유**

QUEUE_FULL(-998)은 사용자 잘못이 아닌 일시적 시스템 상태입니다.
429를 받고 10초를 기다려야 한다면 사용자 경험이 나쁩니다.
Queue가 찼을 때만 rate limit 키를 즉시 삭제해 재시도를 허용합니다.

```java
// ParticipationService.java:37
if (remaining == RedisStockService.QUEUE_FULL) {
    rateLimitService.release(campaignId, userId); // 키 삭제 → 즉시 재시도 가능
    throw new QueueFullException(campaignId);
}
```

**TTL이 10초인 이유**

선착순 특성상 동일 유저가 연속으로 요청을 보내 재고를 여러 번 차감하는 것을 막아야 합니다.
너무 길면 사용자 경험이 나쁘고, 너무 짧으면 중복 요청 차단 효과가 없습니다.
10초는 재시도를 허용하면서도 동일 사용자의 연속 요청을 차단하는 적정 밸런스입니다.
(단, TTL 만료 후 재요청은 participated 영구 키로 별도 차단합니다.)

---

## 4. Lua 원자화 — check-decr-enqueue.lua

```lua
-- check-decr-enqueue.lua
-- KEYS[1] = active:campaign:{campaignId}    (캠페인 활성 플래그)
-- KEYS[2] = stock:campaign:{campaignId}     (재고)
-- KEYS[3] = total:campaign:{campaignId}     (전체 재고 — sequence 계산용)
-- KEYS[4] = queue:campaign:{campaignId}     (Redis Queue)
-- KEYS[5] = participated:campaign:{campaignId}:user:{userId}  (영구 중복 방지)

-- 1. 캠페인 활성 체크
if redis.call('EXISTS', KEYS[1]) == 0 then
    return {-999, 0}   -- INACTIVE_CAMPAIGN
end

-- 2. 영구 중복 참여 체크 (TTL 만료 후 재시도도 차단)
if redis.call('EXISTS', KEYS[5]) == 1 then
    return {-997, 0}   -- ALREADY_PARTICIPATED
end

-- 3. Queue 만원 체크 → 여기서 막히면 DECR 자체 미실행 (핵심!)
if tonumber(redis.call('LLEN', KEYS[4])) >= tonumber(ARGV[1]) then
    return {-998, 0}   -- QUEUE_FULL (재고 차감 없음)
end

-- 4. 재고 차감
local remaining = redis.call('DECR', KEYS[2])
if remaining < 0 then
    return {remaining, 0}   -- 재고 소진
end

-- 5. 마지막 재고 소진 시 active flag 제거 → Bridge가 비활성 감지
if remaining == 0 then
    redis.call('DEL', KEYS[1])
end

-- 6. sequence 계산 + 큐 적재 + 영구 중복 방지 키 동시 등록
local total = tonumber(redis.call('GET', KEYS[3])) or 0
local sequence = total - remaining
local message = '{"campaignId":' .. ARGV[2] .. ',"userId":' .. ARGV[3] .. ',"sequence":' .. sequence .. '}'
redis.call('LPUSH', KEYS[4], message)
redis.call('SET', KEYS[5], '1')   -- TTL 없음 — 영구 보존
return {remaining, total}
```

**왜 단일 Lua 스크립트인가 — 12차 유실 교훈**

수정 전: check-decr-total.lua(DECR) → push-queue.lua(LLEN + LPUSH) 두 번 왕복
수정 후: 하나의 Lua 스크립트로 6개 연산 원자 실행

Redis는 Lua 스크립트를 단일 명령처럼 처리합니다.
스크립트 실행 중에는 다른 클라이언트의 명령이 끼어들 수 없습니다.

```
[수정 전 — 비원자적]
인스턴스A: DECR → remaining=500,000 (차감 완료)
인스턴스B: DECR → remaining=499,999 (차감 완료)
            ...Queue 1M 상한 도달...
인스턴스A: LLEN → 1M ≥ 1M → LPUSH 실패 → 재고는 차감됐지만 Queue에 없음 → 유실

[수정 후 — 원자적]
LLEN → 1.5M 상한 체크 → 꽉 찼으면 DECR 자체 미실행 → -998 반환 → 429
→ 재고 차감 없음 = partial failure 원천 차단
```

**sequence 계산을 Lua 안에서 하는 이유**

`sequence = total - remaining`을 Lua 밖에서 계산하면 DECR 결과와 sequence 계산 사이에 다른 요청이 끼어들 수 있습니다.
sequence는 선착순 순서를 나타내는 값이므로 DECR 직후 원자적으로 계산해야 합니다.

**ElastiCache CME 해시태그**

```
문제: Redis Cluster는 키를 16,384개 슬롯에 분산 저장
      Lua에서 서로 다른 슬롯의 키를 동시 접근 → CROSSSLOT 에러

해결: 키 이름에 {campaignId} 중괄호 포함
      Redis는 중괄호 안의 값만으로 슬롯 계산
      → 같은 campaignId를 가진 모든 키가 동일 슬롯에 배치

active:campaign:{28}  ─┐
stock:campaign:{28}   ─┼→ 모두 슬롯 K → Lua 정상 실행
queue:campaign:{28}   ─┘
```

---

## 5. RedisStockService — 키 설계와 해시태그

```java
// RedisStockService.java
private static final String ACTIVE_CAMPAIGNS_KEY  = "active:campaigns";         // 전역 Set (Bridge 순회용)
private static final String ACTIVE_FLAG_KEY_PREFIX = "active:campaign:{";        // 캠페인별 활성 플래그 (Lua용)
private static final String STOCK_KEY_PREFIX       = "stock:campaign:{";         // 재고 (DECR 대상)
private static final String TOTAL_KEY_PREFIX       = "total:campaign:{";         // 총 재고 (sequence 계산용)
private static final String QUEUE_KEY_PREFIX       = "queue:campaign:{";         // Redis Queue (LPUSH/RPOP)
private static final String PARTICIPATED_KEY_PREFIX = "participated:campaign:{"; // 영구 중복 방지

private static final long MAX_QUEUE_SIZE = 1_500_000;

public static final Long INACTIVE_CAMPAIGN  = -999L;
public static final Long QUEUE_FULL         = -998L;
public static final Long ALREADY_PARTICIPATED = -997L;
```

**키 두 가지 역할 분리 설계**

`active:campaigns` (Set 타입)와 `active:campaign:{id}` (String 타입)가 별도로 존재합니다.

| 키 | 타입 | 용도 | 사용 주체 |
|----|------|------|----------|
| `active:campaigns` | Set | 어떤 캠페인이 활성인지 목록 | Bridge (SMEMBERS로 순회) |
| `active:campaign:{id}` | String | 해당 캠페인이 활성인지 플래그 | Lua 스크립트 (EXISTS 체크) |

Bridge는 전역 Set을 순회해 캠페인 목록을 가져오고,
Lua는 해시태그가 있는 캠페인별 키만 접근합니다.
전역 Set(`active:campaigns`)에는 해시태그가 없어서 Lua 내부에서 접근하면 CROSSSLOT 에러가 발생하므로 분리가 필수입니다.

**활성화 / 비활성화 로직**

```java
// 활성화 — 두 키 모두 등록
public void activateCampaign(Long campaignId) {
    redisTemplate.opsForSet().add(ACTIVE_CAMPAIGNS_KEY, campaignId.toString());
    redisTemplate.opsForValue().set(getActiveFlagKey(campaignId), "1");
}

// 비활성화 — 두 키 모두 정리
public void deactivateCampaign(Long campaignId) {
    redisTemplate.opsForSet().remove(ACTIVE_CAMPAIGNS_KEY, campaignId.toString());
    redisTemplate.delete(getActiveFlagKey(campaignId));
}
```

재고 소진 시(`remaining == 0`) Lua 내부에서 `active:campaign:{id}` 키만 DEL합니다.
Bridge는 Queue가 빌 때 `isActive()` 확인 후 `active:campaigns` Set에서도 제거합니다.
이렇게 순차적으로 정리해 Queue에 남은 메시지가 있는 동안 Bridge가 계속 드레인할 수 있게 합니다.

---

## 6. ParticipationService — 흐름 오케스트레이션

```java
// ParticipationService.java
public void participate(Long campaignId, Long userId) {
    // 1단계: 10초 중복 차단
    if (!rateLimitService.isAllowed(campaignId, userId)) {
        throw new RateLimitExceededException(campaignId, userId);
    }

    // 2단계: Lua 원자 실행 (Redis 왕복 1회)
    long[] result = redisStockService.checkDecrEnqueue(campaignId, userId);
    long remaining = result[0];
    long total     = result[1];

    // 3단계: 결과 코드별 분기
    if (remaining == RedisStockService.INACTIVE_CAMPAIGN) {
        throw new StockExhaustedException(campaignId);   // 400
    }
    if (remaining == RedisStockService.ALREADY_PARTICIPATED) {
        throw new DuplicateParticipationException(campaignId, userId);  // 409
    }
    if (remaining == RedisStockService.QUEUE_FULL) {
        rateLimitService.release(campaignId, userId);    // rate limit 해제
        throw new QueueFullException(campaignId);         // 429
    }
    if (remaining < 0) {
        throw new StockExhaustedException(campaignId);   // 400
    }

    // 4단계: 마지막 재고 소진 시 DB 상태 동기화 (try-catch)
    if (remaining == 0) {
        try {
            campaignRepository.closeAndResetStock(campaignId, CampaignStatus.CLOSED);
        } catch (Exception e) {
            // Lua가 이미 active flag를 DEL했으므로 신규 요청 차단은 유지됨
            // ConsistencyJob이 주기적으로 CLOSED 처리 여부를 검증해 복구
            log.error("캠페인 DB 종료 실패. Redis는 이미 비활성화됨. ...", e);
        }
    }
}
```

**`remaining == 0` 처리에 try-catch가 있는 이유**

T06 장애 테스트에서 발견한 버그입니다.
RDS가 다운된 상태에서 마지막 재고가 소진되면 `campaignRepository.closeAndResetStock()`이 예외를 던지고
이것이 500으로 전파돼 API 전체가 5xx를 반환하는 문제가 있었습니다.

Lua 스크립트는 이미 `active:campaign:{id}` 키를 DEL했으므로, 신규 요청은 -999(INACTIVE)로 거부됩니다.
DB 동기화 실패는 ConsistencyJob이 다음 주기에 탐지해 복구하므로, 이 시점에 500을 전파할 이유가 없습니다.
따라서 try-catch로 감싸고 에러를 로그만 남깁니다.

**DB를 전혀 건드리지 않는 happy path**

정상 참여(remaining >= 1)일 때 이 메서드는 Redis 연산만 수행하고 202를 반환합니다.
DB 접촉은 `remaining == 0` 케이스(마지막 재고 소진)에만 발생합니다.
이것이 v3 Redis-first 아키텍처의 핵심이며 HikariCP pending이 거의 0인 이유입니다.

---

## 7. ParticipationBridge — Redis → Kafka 드레인

```java
// ParticipationBridge.java:65
@Scheduled(fixedDelay = 100)  // 이전 실행 완료 후 100ms 대기
public void drainQueues() {
    drainTimer.record(() -> {
        Set<String> campaignIds = redisTemplate.opsForSet().members(ACTIVE_CAMPAIGNS_KEY);
        if (campaignIds == null || campaignIds.isEmpty()) return;

        for (String campaignIdStr : campaignIds) {
            drainCampaignQueue(Long.parseLong(campaignIdStr));
        }
    });
}

private void drainCampaignQueue(Long campaignId) {
    String queueKey = QUEUE_KEY_PREFIX + campaignId + "}";
    Long queueSize = redisTemplate.opsForList().size(queueKey);
    int dynamicBatchSize = resolveBatchSize(queueSize);

    for (int i = 0; i < dynamicBatchSize; i++) {
        String message = redisTemplate.opsForList().rightPop(queueKey);
        if (message == null) {
            if (!redisStockService.isActive(campaignId)) {
                redisStockService.deactivateCampaign(campaignId);
            }
            break;
        }
        publishAsync(campaignId, message);
    }
}

private int resolveBatchSize(Long queueSize) {
    if (queueSize == null || queueSize < 10_000)  return 500;
    if (queueSize < 100_000)                       return 1_000;
    return 2_000;  // 100K 이상
}
```

**fixedDelay vs fixedRate**

`fixedRate`는 이전 실행이 끝나지 않아도 다음 실행을 시작합니다.
Queue 드레인 작업이 100ms를 넘어가면 중복 실행 문제가 생길 수 있습니다.
`fixedDelay`는 이전 실행이 완료된 후 100ms를 기다리므로 겹침이 없습니다.

**동적 batchSize의 목적**

| Queue 크기 | batchSize | 이유 |
|-----------|----------|------|
| < 10,000 | 500 | 초반/후반 적은 양은 소량씩 처리 |
| < 100,000 | 1,000 | 중간 구간 |
| >= 100,000 | 2,000 | 대량 적재 시 빠른 drain |

테스트 초반에 API 적재 속도 > drain 속도인 구간이 있습니다.
Queue가 1M 이상 쌓이는 구간에서 drain 속도를 최대화하기 위해 batchSize를 동적으로 올립니다.

**userId를 Kafka 파티션 키로 쓰는 이유**

```java
// ParticipationBridge.java:120
private String extractUserIdKey(String message, Long campaignId) {
    ParticipationEvent event = jsonMapper.readValue(message, ParticipationEvent.class);
    return String.valueOf(event.getUserId());  // userId → 파티션 키
}
```

campaignId를 파티션 키로 쓰면 모든 메시지가 동일 파티션으로 쏠립니다.
특정 파티션만 Consumer가 과부하되고 나머지는 유휴 상태가 됩니다.
userId를 키로 쓰면 해시값이 고르게 분산되어 파티션 10개를 균등하게 활용합니다.
(6차 테스트에서 Consumer 지연이 1.25s → 200ms로 개선된 원인입니다.)

**DLQ fallback**

```java
// 비동기 콜백에서 실패 처리
kafkaTemplate.send(TOPIC_NAME, partitionKey, message)
        .whenComplete((result, ex) -> {
            if (ex != null) {
                sendToDlqWithSlack(campaignId, partitionKey, message,
                        ASYNC_SEND_FAILED, ex.getMessage());
            }
        });
```

Kafka publish 실패 시:
1. DLQ Kafka 토픽으로 메시지 이동
2. DB에 DlqMessageRecord 저장 (재처리 추적용)
3. Slack 알림

**레거시 키 fallback**

```java
// Lua 원자화 도입 전 구 키 (해시태그 없음): "queue:campaign:28"
// 도입 후 새 키 (해시태그 포함):            "queue:campaign:{28}"
String legacyKey = LEGACY_QUEUE_KEY_PREFIX + campaignId;
while ((legacyMessage = redisTemplate.opsForList().rightPop(legacyKey)) != null) {
    publishAsync(campaignId, legacyMessage);
}
```

롤링 배포 전환 기간에 구 키와 새 키가 공존할 수 있습니다.
구 키에 남은 메시지를 유실 없이 처리하기 위한 backward-compatibility 코드입니다.

---

## 8. KafkaConfig — Producer/Consumer 설정

### Producer 핵심 설정

```java
// KafkaConfig.java:72
configProps.put(ProducerConfig.ACKS_CONFIG, "all");
// 브로커 리더 + 모든 ISR(In-Sync Replica) 승인 대기
// 데이터 손실 방지 (브로커 1대 다운해도 복제본에 데이터 있음)

configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
// 네트워크 장애로 재전송 시 중복 메시지 방지
// Producer가 각 메시지에 sequence number를 붙여 브로커가 중복 감지

configProps.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
// 일시적 장애 시 무한 재시도
// acks=all + idempotence와 함께 exactly-once semantics에 가까운 동작
```

**acks=all이 왜 중요한가**

- `acks=0`: 브로커 응답 안 기다림 → 가장 빠르지만 데이터 손실 가능
- `acks=1`: 리더만 승인 → 리더 장애 시 손실 가능
- `acks=all`: 모든 ISR 승인 → 가장 안전, 약간의 레이턴시 추가

150만 정합성을 목표로 하는 시스템에서 acks=all은 필수입니다.

### Consumer 핵심 설정

```java
configProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
// 자동 커밋 비활성화 → 처리 완료 후 수동 커밋 (MANUAL ack)
// DB INSERT가 완료된 후에만 오프셋 커밋 → 실패 시 재전달 보장

factory.setBatchListener(true);
// 한 번에 여러 레코드를 배치로 수신 (max.poll.records = 500)

factory.setConcurrency(partitionCount);
// 파티션 수만큼 Consumer 스레드 생성 → 완전 병렬화
```

### 파티션 수 자동 감지

```java
// KafkaConfig.java:142
private int getTopicPartitionCount(String topicName) {
    try (AdminClient adminClient = AdminClient.create(configs)) {
        DescribeTopicsResult result = adminClient.describeTopics(List.of(topicName));
        TopicDescription description = result.allTopicNames().get(5, SECONDS).get(topicName);
        return description.partitions().size();
    }
}
```

파티션 수를 하드코딩하지 않고 애플리케이션 시작 시 AdminClient로 실제 파티션 수를 조회합니다.
파티션을 3→10으로 변경해도 앱 재배포 없이 concurrency가 자동으로 맞춰집니다.
초기화 실패 시 최대 5회 재시도 (Kafka 브로커 시작이 늦을 수 있어서).

---

## 9. ParticipationEventConsumer — 배치 INSERT & ack 제어

```java
// ParticipationEventConsumer.java:43
@KafkaListener(topics = "campaign-participation-topic", groupId = "campaign-participation-group")
public void consumeParticipationEvent(
        List<ConsumerRecord<String, String>> records,
        Acknowledgment acknowledgment
) {
    List<ParticipationEvent> events = parseRecords(records);

    try {
        // 배치 INSERT — 500건을 DB 왕복 1회로 처리
        jdbcTemplate.batchUpdate(
                "INSERT IGNORE INTO participation_history " +
                "(campaign_id, user_id, sequence, status, created_at) " +
                "VALUES (?, ?, ?, 'SUCCESS', NOW())",
                batchArgs
        );
    } catch (Exception batchException) {
        // 배치 실패 시 단건 폴백
        for (ParticipationEvent event : events) {
            try {
                participationHistoryRepository.insertSuccess(...);
            } catch (DataIntegrityViolationException e) {
                // INSERT IGNORE와 동일 효과 — 멱등성 보장
                log.warn("Duplicate success ignored...");
            } catch (Exception e) {
                hasTransientFailure = true;  // RDS 다운 등 일시적 오류
                sendToDlqWithSlack(...);
            }
        }
    }

    // transient failure가 없을 때만 ack → 있으면 Kafka가 재전달
    if (!hasTransientFailure) {
        acknowledgment.acknowledge();
    } else {
        log.warn("Skipping ack due to transient DB failure. Kafka will redeliver...");
    }
}
```

**jdbcTemplate.batchUpdate() vs JPA save()**

| 방식 | DB 왕복 | 500건 처리 |
|------|--------|-----------|
| JPA `save()` 반복 | 500회 | 느림, Consumer 지연 200ms 이상 |
| `batchUpdate()` | 1회 | Consumer 지연 7~20ms |

`rewriteBatchedStatements=true` (JDBC URL 파라미터)와 함께 사용하면 드라이버 레벨에서도 배치 최적화됩니다.

**INSERT IGNORE의 역할 — 멱등성**

ack 보류 → Kafka 재전달 시 동일 레코드가 다시 Consumer에 도달합니다.
`INSERT IGNORE`는 (campaign_id, user_id) UNIQUE 제약 위반 시 에러 없이 무시합니다.
이로써 Consumer가 동일 메시지를 두 번 처리해도 DB에는 한 건만 기록됩니다.

**ack 보류 메커니즘**

```
RDS 다운 상황:
  Consumer: INSERT 실패 → hasTransientFailure = true
  ack 보류 → 오프셋 커밋 안 됨
  SG 복구 후 Kafka가 자동으로 동일 메시지 재전달
  Consumer: INSERT 성공 → ack → 오프셋 커밋
```

T06(RDS SG 삭제 장애 테스트)에서 이 경로를 실제로 검증했습니다.

**파싱 실패 처리**

```java
private List<ParticipationEvent> parseRecords(...) {
    if (event.getSequence() == null) {
        sendToDlqWithSlack(record.key(), record.value(), "MISSING_SEQUENCE", null);
        continue;  // 이 레코드 건너뜀 (나머지는 정상 처리)
    }
}
```

파싱 실패한 레코드는 DLQ로 보내고 배치 전체를 롤백하지 않습니다.
하나의 오염된 메시지 때문에 정상 메시지 499건을 재처리할 이유가 없습니다.

---

## 10. ConsistencyRecoveryService — 이상 탐지 & 자동 복구

**탐지하는 이상 유형**

```java
// ConsistencyRecoveryService.java:142
private List<AnomalyDecision> detectAnomalies(CampaignRuntimeSnapshot snapshot) {
```

| AnomalyType | 설명 | 심각도 | 자동 복구 |
|-------------|------|--------|---------|
| `SUCCESS_PENDING_EXCEEDS_TOTAL` | DB 처리 건수 > 총 재고 | CRITICAL | REPORT_ONLY |
| `NEGATIVE_REDIS_STOCK` | Redis 재고가 음수 | CRITICAL | REPORT_ONLY |
| `MISSING_REDIS_STOCK` | Redis 재고 키가 없음 (재시작 등) | WARNING | RESTORE_REDIS_STATE |
| `MISSING_REDIS_TOTAL` | Redis total 키가 없음 | WARNING | RESTORE_REDIS_STATE |
| `REDIS_REMAINING_MISMATCH` | Redis 재고 ≠ DB 기반 예상 재고 | CRITICAL | REPORT_ONLY |
| `CLOSED_REDIS_RESIDUE` | 종료된 캠페인에 Redis 데이터 잔류 | WARNING | CLEANUP_REDIS_STATE |
| `MISSING_ACTIVE_STATE` | OPEN 캠페인이 Redis에 비활성 상태 | WARNING | ACTIVATE_CAMPAIGN |

**DryRun / AutoFix 모드**

```java
if (execution.isDryRun()) {
    // 실제 수정 없이 탐지 결과만 반환
} else if (!execution.isAutoFix() || !decision.fixable()) {
    // 리포트만 (자동 수정 안 함)
} else {
    fixed = applyFix(snapshot, decision.action());
}
```

세 가지 모드를 지원합니다:
- `dryRun=true`: 탐지만, 수정 없음 (안전하게 먼저 확인)
- `autoFix=false`: 탐지 + 결과 리포트, 수정 없음
- `autoFix=true`: 탐지 + fixable한 항목 자동 수정

**T04 테스트에서 검증한 복구 경로**

```
Redis stock 키 강제 삭제 → MISSING_REDIS_STOCK 탐지 →
RESTORE_REDIS_STATE: DB 기반 remaining 계산 후 Redis 복구
  remaining = totalStock - successCount - pendingCount
```

복구 공식에서 pendingCount도 빼는 이유: PENDING 상태는 아직 처리 중인 건이므로 이미 소진된 재고입니다.

---

## 11. PendingRecoveryJob — PENDING 메시지 재발행

```java
// PendingRecoveryJobConfig.java:58
LocalDateTime cutoff = now.minusMinutes(5);
List<ParticipationHistory> pendingList =
        participationHistoryRepository.findByStatusAndCreatedAtBefore(
                ParticipationStatus.PENDING, cutoff);

for (ParticipationHistory history : pendingList) {
    kafkaTemplate.send(KafkaConfig.TOPIC_NAME,
            String.valueOf(history.getCampaign().getId()), message);
}
```

**PENDING 상태가 5분 이상 유지되는 상황**

정상 흐름에서는 PENDING이 발생하지 않습니다.
PENDING은 Consumer 장애 시 Bridge가 DB에 중간 상태를 기록하는 경우인데,
현재 코드에서는 Bridge → Kafka만 담당하고 PENDING DB 기록이 없으므로
이 Job은 레거시 구조 잔재 또는 향후 두 단계 확인(2PC-like)을 위한 안전망입니다.

Kafka 메시지 재발행 시 Consumer의 `INSERT IGNORE`가 중복을 처리합니다.

---

## 12. StockRecoveryScheduler — Redis 재시작 복구

```java
// StockRecoveryScheduler.java:27
@Scheduled(fixedDelay = 60_000)  // 1분마다
public void syncRedisStockFromDb() {
    List<Campaign> activeCampaigns = campaignRepository.findByStatus(CampaignStatus.OPEN);

    for (Campaign campaign : activeCampaigns) {
        if (redisStockService.hasStock(campaign.getId())) {
            continue;  // stock 키 있으면 스킵
        }

        // Redis 재시작 등으로 키가 유실된 경우
        long restoreStock = Math.max(totalStock - successCount - pendingCount, 0);
        redisStockService.initializeStock(campaignId, restoreStock);
        redisStockService.initializeTotal(campaignId, totalStock);
        redisStockService.activateCampaign(campaignId);
    }
}
```

**ConsistencyRecoveryService와의 차이**

| | StockRecoveryScheduler | ConsistencyRecoveryService |
|--|----------------------|--------------------------|
| 실행 방식 | 자동 (1분마다) | 수동 트리거 (API 또는 배치) |
| 대상 | stock 키가 없는 OPEN 캠페인만 | 모든 캠페인 (이상 탐지 전체) |
| 복구 범위 | Redis stock/total/active 복원 | 탐지 + 복구 + 이력 기록 |
| 설계 의도 | Redis 재시작 후 빠른 자동 복구 | 운영자가 제어하는 정밀 복구 |

**`Math.max(..., 0)`의 이유**

매우 드물게 successCount + pendingCount > totalStock인 이상 상황에서 음수 재고로 초기화되는 것을 방지합니다.
ConsistencyJob이 이 이상을 `SUCCESS_PENDING_EXCEEDS_TOTAL`로 탐지해 별도 리포트합니다.

---

## 13. 면접 예상 질문 & 답변

### Q. 왜 Kafka를 쓰셨나요? Redis Queue만으로 안 되나요?

**답변:**
Redis Queue(List)는 휘발성입니다. Redis 재시작 시 메시지가 사라집니다.
Kafka는 메시지를 디스크에 영구 저장하고 Consumer 오프셋을 관리해
"적어도 한 번(at-least-once)" 처리를 보장합니다.
또한 파티션으로 병렬 Consumer를 구성해 처리량을 선형으로 확장할 수 있습니다.
현재 구조에서 Redis Queue는 API → Kafka 사이의 임시 버퍼 역할만 합니다.
실제 내구성 보장은 Kafka가 담당합니다.

---

### Q. API가 202를 반환했는데 DB에 없을 수 있지 않나요?

**답변:**
의도적인 설계입니다. 202는 "처리 중"을 의미하고 "완료"를 보장하지 않습니다.
다만 시스템은 여러 층의 보호를 통해 최종 DB 기록을 보장합니다:

1. Redis Queue → Kafka: Bridge가 100ms마다 드레인, Kafka publish 실패 시 DLQ
2. Kafka → DB: Consumer ack 보류 + Kafka 자동 재전달, INSERT IGNORE 멱등성
3. 잔여 PENDING: PendingRecoveryJob이 5분 초과 건 재발행
4. 이상 탐지: ConsistencyRecoveryService가 주기적으로 검증

14차 테스트에서 160만 요청 중 150만 건이 최종적으로 DB에 정확히 기록됨을 확인했습니다.

---

### Q. Lua 스크립트에서 Redis 연산 실패 시 롤백이 되나요?

**답변:**
됩니다만, 주의점이 있습니다.
Redis Lua 스크립트는 원자적으로 실행되지만 트랜잭션과 다릅니다.
`DECR`이 성공하고 `LPUSH`가 실패해도 `DECR` 결과가 자동 롤백되지 않습니다.
이것이 12차에서 유실이 발생한 원인이었습니다.

해결 방법은 순서를 바꾸는 것입니다.
`LLEN` 체크 → Queue 꽉 찼으면 `DECR` 자체를 실행하지 않음.
Queue full 시 -998을 반환하고 이후 연산 전체를 스킵합니다.
즉, "실패 후 롤백"이 아닌 "실패 가능성을 사전에 차단"하는 방식입니다.

---

### Q. ElastiCache CME(Cluster Mode Enabled) 환경에서 Lua 멀티키를 어떻게 해결했나요?

**답변:**
Redis Cluster는 키를 16,384개 슬롯에 해시로 분산합니다.
Lua 스크립트에서 서로 다른 슬롯의 키를 접근하면 CROSSSLOT 에러가 발생합니다.

해시태그(Hash Tag) 기법을 사용했습니다.
키 이름에 `{campaignId}` 중괄호를 포함하면 Redis는 중괄호 안의 값만으로 슬롯을 계산합니다.
`active:campaign:{28}`, `stock:campaign:{28}`, `queue:campaign:{28}` 모두 28을 기준으로
동일한 슬롯에 배치되어 Lua에서 함께 접근할 수 있습니다.

---

### Q. HikariCP pool size를 얼마로 설정했고 그 이유는?

**답변:**
Consumer 측에 pool size 20을 사용했습니다.
`jdbcTemplate.batchUpdate()`는 배치 단위로 커넥션을 획득하고 즉시 반환합니다.
concurrency 10(파티션 수)이 동시에 실행될 때 최대 10개 커넥션이 필요합니다.
20은 여유분을 포함한 설정입니다.

v1에서 HikariCP pending이 950~980에 달했던 이유는 API 스레드 각각이
DB 커넥션을 장시간 점유하면서 Row Lock 대기가 겹쳤기 때문입니다.
v3에서는 API 경로에서 DB를 완전히 제거해 이 문제가 사라졌습니다.

---

### Q. 선착순 순서(sequence)가 정확한가요?

**답변:**
`sequence = total - remaining`을 Lua 스크립트 내부에서 계산합니다.
`DECR`이 반환한 `remaining`과 `GET total`이 단일 원자 실행 안에 있으므로
다른 요청이 끼어들어 sequence가 중복되거나 건너뛰는 경우가 없습니다.

예: total=1,000,000, remaining=999,999 → sequence=1 (첫 번째 참여자)
    total=1,000,000, remaining=999,998 → sequence=2 (두 번째 참여자)

단, Consumer가 Kafka 파티션에서 병렬로 DB에 INSERT하므로
DB 기록 순서가 sequence 순서와 반드시 일치하지는 않습니다.
"처리된 순서"가 아닌 "요청이 수락된 순서"가 sequence 기준입니다.

---

### Q. 장애 테스트는 어떻게 진행했나요?

**답변:**
실제 AWS 환경에서 7가지 시나리오를 직접 주입했습니다.

- **T01** — 동일 userId로 연속 요청: rate limit 429 + participated 키로 영구 차단 확인
- **T03** — MAX_QUEUE_SIZE를 500K로 낮춰 Queue를 의도적으로 꽉 채움: -998 반환 + 재고 차감 없음 + Redis 잔여재고 + DB = total_stock 확인
- **T05** — Kafka 브로커 1대 EC2 중지: KRaft 자동 리더 재선출, lag 스파이크 → 즉시 해소, 5xx 0건
- **T06** — RDS Security Group 3306 삭제: Consumer ack 보류 → SG 복구 후 재전달 자동 처리, diff=0 확인. 이 과정에서 remaining==0 시 500 전파 버그 발견 및 try-catch로 수정
- **T07** — ASG 인스턴스 1대 강제 terminate: TPS 80% 급락 → 3~4분 내 ASG 자동 교체 및 완전 복구, 5xx 0건

---

## 14. 설정 파일 — application.yml & application-prod.yml

### application.yml (공통 설정)

**Virtual Thread 활성화**

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

Java 25 + Spring Boot 4.0.4에서 Virtual Thread를 활성화합니다.
OS 스레드 대신 JVM이 관리하는 경량 스레드를 사용해 수만 개의 동시 요청을 적은 메모리로 처리합니다.
블로킹 I/O(Redis, Kafka 호출) 동안 OS 스레드를 점유하지 않아 스레드 풀 소진이 없습니다.
기존 스레드 풀(Tomcat 기본 200개)로는 TPS 수천 수준에서 포화되지만, Virtual Thread는 요청마다 새 스레드를 생성해도 메모리가 거의 들지 않습니다.

**p95/p99 히스토그램 + SLO**

```yaml
management:
  metrics:
    distribution:
      percentiles-histogram:
        "[http.server.requests]": true   # 히스토그램 버킷 활성화
      percentiles:
        "[http.server.requests]": 0.95, 0.99
      slo:
        "[http.server.requests]": 1s, 3s  # 1초/3초 경계를 SLO 버킷으로 등록
```

`percentiles-histogram: true` — Prometheus에서 `histogram_quantile()`로 임의 분위수 계산 가능.
`slo: 1s, 3s` — 요청이 1초/3초 이내에 처리된 비율을 별도 버킷으로 노출합니다.
Grafana 알람 임계값 기준: 1초 초과 비율이 높으면 WARNING, 3초 초과 시 CRITICAL.

### application-prod.yml (프로덕션 전용)

**HikariCP 커넥션 풀 설정**

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 10
      connection-timeout: 20000   # 20초 (커넥션 획득 대기 한도)
      idle-timeout: 300000        # 5분 (유휴 커넥션 반환)
      max-lifetime: 1800000       # 30분 (커넥션 최대 수명)
```

`maximum-pool-size: 20` — Consumer concurrency(10파티션) 기준 2배 여유.
v3 Redis-first 구조에서 API 경로는 DB를 사용하지 않으므로, 커넥션을 점유하는 주체는
Consumer(10) + ConsistencyJob + PendingRecoveryJob 등 배치성 작업뿐입니다.
v1에서 `pending 980`이 발생했던 것과 대조됩니다.

**Kafka Producer 핵심 설정**

```yaml
kafka:
  producer:
    buffer-memory: 536870912   # 512MB 버퍼 (대량 메시지 버퍼링)
    batch-size: 131072         # 128KB 배치 (묶어서 전송)
    linger-ms: 5               # 5ms 대기 후 배치 전송 (처리량 vs 레이턴시 균형)
    acks: all
    enable-idempotence: true
```

`buffer-memory: 512MB` — 초당 수천 건의 메시지를 메모리에 버퍼링해 브로커 왕복을 줄입니다.
`linger-ms: 5` — 5ms 동안 메시지를 모아 한 번에 전송해 배치 효율을 높입니다.
`acks: all + enable-idempotence: true` — 브로커 장애 시에도 손실/중복 없음.

**Kafka Consumer 핵심 설정**

```yaml
kafka:
  consumer:
    max-poll-records: 100          # 한 번에 최대 100건 수신
    max-poll-interval-ms: 600000   # 10분 (배치 INSERT 처리 시간 보장)
    session-timeout-ms: 45000      # 45초 heartbeat 타임아웃
    replication-factor: 3
    partitions: 10
```

`max-poll-records: 100` — 배치 INSERT와 연계해 100건을 DB 왕복 1회로 처리.
`max-poll-interval-ms: 600000` — 배치 처리가 길어질 때 Consumer가 불필요한 rebalance를 하지 않도록 넉넉히 설정.

**Redis CME 클러스터 설정**

```yaml
spring:
  data:
    redis:
      cluster:
        nodes: ${SPRING_DATA_REDIS_CLUSTER_NODES}  # SSM에서 런타임 주입
        max-redirects: 3
```

노드 주소를 환경변수로 받아 SSM에서 주입합니다.
ElastiCache를 재생성해 엔드포인트가 바뀌어도 SSM만 갱신하면 다음 배포에 자동 반영됩니다.
`max-redirects: 3` — 클러스터 MOVED 응답(키가 다른 노드에 있을 때) 리다이렉션 최대 횟수.

**CloudWatch 메트릭 Export**

```yaml
management:
  metrics:
    export:
      cloudwatch:
        enabled: true
        namespace: CampaignSystem/V2
        step: 1m   # 1분 단위 Push
```

Micrometer CloudWatch MeterRegistry가 커스텀 메트릭을 CloudWatch에 자동 Push합니다.
Grafana(Prometheus)와 CloudWatch 콘솔 양쪽에서 동일한 메트릭을 조회할 수 있습니다.

---

## 15. CI/CD 파이프라인 — GitHub Actions + CodeDeploy

### deploy.yml 전체 흐름

```
트리거: main 브랜치 push + app/campaign-core/** 변경 감지
        (concurrency: cancel-in-progress — 이전 배포 자동 취소)
        │
        ▼
[1] OIDC 토큰 발급 → AWS AssumeRoleWithWebIdentity
    Access Key 없이 임시 자격증명 획득 (15분~1시간 유효)
        │
        ▼
[2] ECR 로그인 → Gradle 빌드 → docker build
    이미지 태그: sha-{GITHUB_SHA[:7]}
        │
        ▼
[3] docker push → ECR
        │
        ▼
[4] SSM PutParameter: /batch-kafka/prod/ECR_IMAGE 갱신
    (EC2 배포 시 beforeInstall.sh가 이 값을 읽어 최신 이미지 pull)
        │
        ▼
[5] S3 업로드 — 배포 번들 (appspec.yml + deploy/ + stress-test/)
        │
        ▼
[6] CodeDeploy CreateDeployment 트리거
        │
        ▼
[7~8] CodeDeploy → ASG 인스턴스에 OneAtATime 순차 배포
      ValidateService 통과 시 다음 인스턴스로 진행
      실패 시 이전 버전으로 자동 롤백
```

### OIDC 인증 — Access Key 0개 원칙

```yaml
# deploy.yml
- uses: aws-actions/configure-aws-credentials@v4
  with:
    role-to-assume: arn:aws:iam::631124976154:role/github-actions-campaign-deploy-role
    aws-region: ap-northeast-2
```

GitHub Actions가 실행 시 JWT 토큰을 생성하고 AWS STS `AssumeRoleWithWebIdentity`로 임시 자격증명을 교환합니다.

IAM Role Trust Policy에 조건이 있습니다:
```json
"Condition": {
  "StringLike": {
    "token.actions.githubusercontent.com:sub": "repo:hskhsmm/1milion-campaign-orchestration-system:*"
  }
}
```

이 레포에서 발행된 토큰만 Role을 assume할 수 있습니다.
다른 GitHub 레포에서 동일 Role을 탈취하는 것을 차단합니다.

### concurrency cancel-in-progress

```yaml
concurrency:
  group: deploy-${{ github.ref }}
  cancel-in-progress: true
```

동일 브랜치 push가 연속으로 발생할 때 이전 배포를 취소하고 최신 커밋만 배포합니다.
CodeDeploy가 진행 중인 배포와 신규 배포가 충돌하는 것을 방지합니다.

### CodeDeploy 배포 전략

```hcl
# codedeploy.tf
deployment_config_name = "CodeDeployDefault.OneAtATime"

deployment_style {
  deployment_option = "WITHOUT_TRAFFIC_CONTROL"
  deployment_type   = "IN_PLACE"
}

auto_rollback_configuration {
  enabled = true
  events  = ["DEPLOYMENT_FAILURE"]
}
```

`OneAtATime` — ASG 인스턴스를 1대씩 순서대로 배포합니다.
배포 중 나머지 인스턴스는 트래픽을 계속 받으므로 다운타임 없이 롤링 업데이트가 가능합니다.
`DEPLOYMENT_FAILURE` 감지 시 이전 이미지로 자동 롤백됩니다.

### 배포 스크립트 4단계

| 훅 | 스크립트 | 주요 동작 |
|----|---------|---------|
| BeforeInstall | `beforeInstall.sh` | ECR 로그인 + SSM 8개 파라미터 fetch → `/opt/campaign-core/.env.prod` (chmod 600) |
| AfterInstall | `afterInstall.sh` | `.env.prod`에서 ECR_IMAGE 읽어 `docker pull` |
| ApplicationStart | `applicationStart.sh` | `docker-compose down` → `docker-compose up -d` + stress-test 스크립트 동기화 |
| ValidateService | `validateService.sh` | `/actuator/health` UP 응답 확인 (30회 × 2초 = 최대 60초) |

**beforeInstall.sh 핵심 로직**

```bash
fetch_param() {
  local val
  val=$(aws ssm get-parameter --name "$name" --with-decryption \
        --query Parameter.Value --output text 2>/dev/null || true)
  echo "$key=$val" >> "${ENV_FILE}"
}

fetch_param SPRING_DATASOURCE_PASSWORD     "/batch-kafka/prod/SPRING_DATASOURCE_PASSWORD"
fetch_param SPRING_DATA_REDIS_CLUSTER_NODES "/batch-kafka/prod/SPRING_DATA_REDIS_CLUSTER_NODES"
# ... 8개 파라미터

chmod 600 "${ENV_FILE}"  # 소유자만 읽기 가능
```

비밀값이 코드에 없습니다. 배포 시점에 SSM에서 가져와 EC2 로컬 파일에만 씁니다.
ElastiCache를 재생성해 Redis 주소가 바뀌어도 SSM만 갱신하면 다음 배포에 자동 반영됩니다.

---

## 16. Terraform 인프라 설계

### 전체 리소스 맵

```
infra/
├── vpc.tf             — VPC, 서브넷(퍼블릭/프라이빗), 라우팅, NAT GW
├── security_groups.tf — 포트별 SG (최소 권한 원칙)
├── asg.tf             — Launch Template, ASG, Target Tracking 정책
├── alb.tf             — ALB, Target Group, Listener
├── elasticache.tf     — CME 3샤드, SSM 자동 등록
├── rds.tf             — MySQL 8.0.44, 암호화, 슬로우 쿼리 로그
├── iam.tf             — OIDC Provider, GitHub Actions Role, 최소 권한 정책
├── codedeploy.tf      — CodeDeploy App + Deployment Group + 자동 롤백
├── ec2.tf             — Kafka 3-broker EC2
└── main.tf / variables.tf / outputs.tf
```

### ASG — Launch Template & Target Tracking

```hcl
# asg.tf
resource "aws_autoscaling_group" "app" {
  min_size         = 2
  max_size         = 3
  desired_capacity = 2

  target_group_arns = [aws_lb_target_group.app.arn]

  lifecycle {
    ignore_changes = [desired_capacity, min_size, max_size]
    # 콘솔에서 desired=3으로 변경 후 terraform apply 시 되돌아가지 않도록 보호
  }
}

resource "aws_autoscaling_policy" "cpu_tracking" {
  policy_type = "TargetTrackingScaling"

  target_tracking_configuration {
    predefined_metric_specification {
      predefined_metric_type = "ASGAverageCPUUtilization"
    }
    target_value       = 60.0   # CPU 60% 유지 목표
    scale_out_cooldown = 60     # 스케일아웃 후 60초 대기
    scale_in_cooldown  = 300    # 스케일인 후 300초 대기
  }
}
```

**`ignore_changes = [desired_capacity, min_size, max_size]` 이유**

부하 테스트 시 콘솔에서 `desired=3`으로 직접 올립니다.
이 상태에서 terraform apply를 하면 코드의 `desired=2`로 롤백되는 문제가 있습니다.
`ignore_changes`로 Terraform이 이 값들을 건드리지 않도록 보호합니다.
ASG 스케일 조정은 콘솔 또는 Target Tracking 정책이 담당합니다.

**Target Tracking CPU 60% 선택 이유**

60% 기준으로 스케일아웃하면 새 인스턴스가 준비되는 동안 기존 인스턴스에 버퍼(40%)가 남습니다.
80~90%를 목표로 하면 새 인스턴스 시작 전에 CPU가 100%에 도달해 응답이 급격히 느려집니다.
14차 테스트에서 앱 CPU 97% 도달 → 이것이 현재 t3.small 3대의 한계 지점입니다.

### ElastiCache CME 3샤드 설계

```hcl
# elasticache.tf
resource "aws_elasticache_replication_group" "redis" {
  engine         = "valkey"
  engine_version = "7.2"
  node_type      = "cache.t3.micro"

  cluster_mode {
    num_node_groups         = 3  # 16,384 슬롯을 3등분
    replicas_per_node_group = 1  # Primary 1 + Replica 1 = 샤드당 2노드 → 총 6노드
  }

  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
}

# terraform apply 시 SSM 자동 갱신
resource "aws_ssm_parameter" "redis_cluster_nodes" {
  name  = "/batch-kafka/prod/SPRING_DATA_REDIS_CLUSTER_NODES"
  type  = "SecureString"
  value = aws_elasticache_replication_group.redis.configuration_endpoint_address
}
```

**CME 선택 이유**

단일 노드는 메모리/연결 한계가 있습니다.
CME는 데이터를 3샤드에 분산해 수평 확장이 가능합니다.
Replica 1개씩 → Primary 장애 시 자동 승격 (고가용성).
단, 멀티키 Lua 스크립트는 동일 샤드 내 키만 접근 가능 → 해시태그({campaignId}) 필수.

**SSM 자동 등록 이유**

ElastiCache 재생성 시 엔드포인트가 바뀝니다.
`terraform apply`가 SSM을 직접 갱신하므로, 다음 CodeDeploy 배포 시 새 주소가 자동 반영됩니다.
코드와 설정 파일에 ElastiCache 주소가 없습니다.

### Security Group 최소 권한 설계

```
인터넷 (0.0.0.0/0)
    │ :80/:443
    ▼
  ALB SG
    │ :8080 (ALB SG에서만)
    ▼
  App SG (ASG t3.small)
    │ :3306          │ :9092            │ :6379
    ▼                ▼                  ▼
  RDS SG         Kafka SG          ElastiCache SG
 (App only)  (App+Kafka self       (App+MCP only)
              +MCP :9092,
               self :9094)
```

주요 규칙:
- RDS 3306: App SG에서만 → 직접 DB 접속 차단
- Kafka 9092: App + Kafka 자기 자신(브로커 간 통신) + MCP(모니터링)
- Kafka 9094: Kafka 자기 자신만 (KRaft 내부 컨트롤러 통신)
- ElastiCache 6379: App + MCP만
- SSH(22): 전체 차단 → SSM Session Manager로만 접속

### RDS 설계 포인트

```hcl
# rds.tf
resource "aws_db_parameter_group" "slow" {
  parameter { name = "slow_query_log"  value = "1"     }
  parameter { name = "long_query_time" value = "0.1"   }  # 0.1초 이상 기록
  parameter { name = "log_output"      value = "TABLE" }  # mysql.slow_log 테이블
}

resource "aws_db_instance" "batch_kafka_db" {
  engine_version    = "8.0.44"
  instance_class    = "db.t3.micro"
  storage_encrypted = true               # KMS 암호화
  multi_az          = false              # 포트폴리오 비용 절감
  storage_type      = "gp3"

  lifecycle {
    ignore_changes = [password]          # Terraform이 패스워드 초기화 안 함
  }
}
```

슬로우 쿼리 로그 0.1초 기준 — 병목 쿼리 탐지용. 실제로 v3 전환 후 슬로우 쿼리가 거의 없음.
`lifecycle.ignore_changes = [password]` — SSM에서 직접 관리하는 비밀번호를 terraform apply가 덮어쓰지 않도록 보호.

### IAM — OIDC Provider + 최소 권한 Role

```hcl
# iam.tf
resource "aws_iam_openid_connect_provider" "github_actions" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

resource "aws_iam_role" "github_actions" {
  name = "github-actions-campaign-deploy-role"

  assume_role_policy = jsonencode({
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = "arn:aws:iam::...:oidc-provider/token.actions.githubusercontent.com" }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringLike = {
          "token.actions.githubusercontent.com:sub" = "repo:hskhsmm/1milion-campaign-orchestration-system:*"
        }
      }
    }]
  })
}
```

GitHub Actions Role이 가진 권한 (최소 권한):
- ECR: `AmazonEC2ContainerRegistryFullAccess` (이미지 push)
- S3: `batch-kafka-deploy-*` 버킷만 (PutObject, GetObject, DeleteObject, ListBucket)
- SSM: `/batch-kafka/prod/*` 읽기 + `/batch-kafka/prod/ECR_IMAGE` 쓰기만
- CodeDeploy: `AWSCodeDeployFullAccess`

다른 버킷 접근, EC2 직접 조작, IAM 수정 권한은 없습니다.

---

## 17. 커스텀 메트릭 & 모니터링 컴포넌트

### 커스텀 메트릭 4종 (Grafana 패널)

| 메트릭 이름 | 타입 | 측정 위치 | Grafana 활용 |
|------------|------|---------|------------|
| `redis.queue.size` | Gauge | QueueMetricsScheduler (10초) | Queue 적재량 실시간 확인 |
| `bridge.drain.duration` | Timer | ParticipationBridge drainTimer | Bridge 사이클 처리 시간 |
| `bridge.drain.cycle` | Counter | ParticipationBridge | 드레인 사이클 횟수 |
| `consumer.process.latency` | Timer | ParticipationEventConsumer | Kafka → DB INSERT 지연 |

### QueueMetricsScheduler — Redis Queue 크기 Gauge

```java
// QueueMetricsScheduler.java
@Scheduled(fixedDelay = 10_000)
public void updateQueueMetrics() {
    Set<String> campaignIds = redisTemplate.opsForSet().members(ACTIVE_CAMPAIGNS_KEY);
    if (campaignIds == null) return;

    for (String campaignIdStr : campaignIds) {
        Long queueSize = redisTemplate.opsForList().size(queueKey);

        // Map에 최신 값 저장 — Gauge가 이 Map을 참조
        queueSizes.put(campaignIdStr, queueSize != null ? queueSize : 0L);

        // 같은 campaignId로 중복 등록 방지 — 최초 1회만 Gauge 생성
        gaugeMap.computeIfAbsent(campaignIdStr, id ->
            Gauge.builder("redis.queue.size", queueSizes, map -> map.getOrDefault(id, 0L))
                 .tag("campaignId", id)
                 .register(meterRegistry)
        );
    }
}
```

**Gauge 설계 포인트**

`Gauge`는 현재 값을 폴링할 때 읽는 메트릭입니다.
`ConcurrentHashMap`에 값을 저장하고 Gauge가 람다로 참조합니다.
`computeIfAbsent`로 캠페인당 한 번만 Gauge를 등록하고, 이후 값 갱신은 Map 업데이트만 합니다.
(동일 이름으로 Gauge를 중복 등록하면 Micrometer에서 예외가 발생합니다.)

**이 메트릭이 필요한 이유**

ParticipationBridge의 drain 속도와 API 적재 속도 차이를 시각화합니다.
테스트 초반 `Queue 크기 증가 구간` (적재 > drain)과 이후 `안정화 구간`을 확인할 수 있습니다.
14차 테스트에서 Queue가 최대 1.2M까지 쌓인 것을 이 메트릭으로 확인했습니다.
MAX_QUEUE_SIZE(1.5M) 임박 여부를 실시간으로 모니터링해 확장 판단에 활용합니다.

### PerformanceMonitoringAspect — AOP 응답시간 측정

```java
// PerformanceMonitoringAspect.java
@Aspect
@Component
public class PerformanceMonitoringAspect {

    @Around("execution(* io.eventdriven.campaign.api..*Controller.*(..))")
    public Object measurePerformance(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();
        try {
            return joinPoint.proceed();
        } finally {
            long elapsed = System.currentTimeMillis() - start;
            log.info("[PERF] {}.{} {}ms",
                joinPoint.getTarget().getClass().getSimpleName(),
                joinPoint.getSignature().getName(),
                elapsed);
        }
    }
}
```

`@RestController` 전체 메서드에 AOP로 실행시간을 측정합니다.
컨트롤러 코드를 수정하지 않고 횡단 관심사(cross-cutting concern)로 분리합니다.
Spring Actuator `http.server.requests` 메트릭과 비교해 프레임워크 오버헤드를 측정하는 용도로 활용합니다.

---

## 18. DlqReplayService — DLQ 재처리 정책

```java
// DlqReplayService.java
public DlqReplayResult replay(Long dlqMessageId, DlqReplayPolicy policy, boolean dryRun) {
    DlqMessageRecord record = dlqRepository.findById(dlqMessageId)
        .orElseThrow(() -> new IllegalArgumentException("DLQ 메시지를 찾을 수 없습니다: " + dlqMessageId));

    if (dryRun) {
        // 실제 처리 없이 결과 시뮬레이션 — 운영 실수 방지
        return DlqReplayResult.dryRun(record);
    }

    return switch (policy) {
        case REPLAY -> {
            kafkaTemplate.send(TOPIC_NAME, record.getPartitionKey(), record.getMessage());
            record.setStatus(DlqStatus.REPLAYED);
            replayCounter.increment(Tags.of("policy", "REPLAY"));
            yield DlqReplayResult.success(record);
        }
        case SKIP -> {
            record.setStatus(DlqStatus.SKIPPED);
            replayCounter.increment(Tags.of("policy", "SKIP"));
            yield DlqReplayResult.skipped(record);
        }
        case FINAL_FAIL -> {
            record.setStatus(DlqStatus.FINAL_FAIL);
            slackNotificationService.sendDlqFinalFailAlert(record);
            replayCounter.increment(Tags.of("policy", "FINAL_FAIL"));
            yield DlqReplayResult.finalFail(record);
        }
    };
}
```

**정책 선택 기준**

| 정책 | 사용 시점 | 결과 |
|------|---------|------|
| `REPLAY` | 일시적 장애(RDS 다운 등) 복구 후 재처리 | Kafka 재발행 → Consumer INSERT IGNORE로 처리 |
| `SKIP` | 오염된 메시지, 재처리 불필요 확인된 경우 | DB에 SKIPPED 기록, 재처리 없음 |
| `FINAL_FAIL` | 복구 불가 판단, 수동 처리 필요 | Slack 알림 + FINAL_FAIL 기록 |

**dryRun 모드**

실제 재처리 전 "이 메시지를 REPLAY하면 어떻게 되는가"를 시뮬레이션합니다.
운영 중 DLQ를 잘못 처리하는 실수를 방지합니다.
MCP 서버에서 `trigger_consistency_check` 도구 호출 시 dryRun으로 먼저 확인한 뒤 결정합니다.

**Micrometer Counter — 정책별 처리 현황 추적**

각 정책 실행 시 `dlq.replay.count{policy="REPLAY|SKIP|FINAL_FAIL"}` 카운터를 증가시킵니다.
Grafana에서 정책별 DLQ 처리 현황을 시각화해 운영 패턴을 파악합니다.

**REPLAY 후 중복 방지**

REPLAY 시 동일 메시지가 Kafka를 통해 Consumer에 다시 도달합니다.
Consumer의 `INSERT IGNORE` + (campaign_id, user_id) UNIQUE 제약이 중복 INSERT를 막습니다.
따라서 REPLAY를 여러 번 실행해도 DB에는 한 건만 기록됩니다.

---

## 19. 보안 설계 — OIDC · SSM · Security Group

### 비밀값 관리 원칙 — 코드에 비밀 없음

```
코드 레포 (GitHub): 비밀값 없음, 환경변수 플레이스홀더만
                    ${SPRING_DATASOURCE_PASSWORD}
                    ${SPRING_DATA_REDIS_CLUSTER_NODES}

AWS SSM Parameter Store (SecureString, KMS 암호화):
  /batch-kafka/prod/SPRING_DATASOURCE_PASSWORD
  /batch-kafka/prod/SPRING_DATASOURCE_URL
  /batch-kafka/prod/SPRING_DATASOURCE_USERNAME
  /batch-kafka/prod/SPRING_KAFKA_BOOTSTRAP_SERVERS
  /batch-kafka/prod/SPRING_DATA_REDIS_CLUSTER_NODES
  /batch-kafka/prod/SLACK_WEBHOOK_URL
  /batch-kafka/prod/ECR_IMAGE           ← CI/CD가 배포 시 자동 갱신
  /batch-kafka/prod/SPRING_PROFILES_ACTIVE
```

비밀값이 GitHub Secrets에도 없습니다. OIDC Role을 assume하면 SSM 읽기 권한이 생기므로
deploy 시점에만 SSM에서 가져와 EC2 로컬(`.env.prod`, chmod 600)에 씁니다.

### OIDC 인증 흐름

```
GitHub Actions 실행
    │
    ▼
GitHub OIDC Provider가 JWT 토큰 발급
    │  토큰 포함 정보:
    │  sub: "repo:hskhsmm/1milion-campaign-orchestration-system:ref:refs/heads/main"
    ▼
AWS STS AssumeRoleWithWebIdentity
    │  Trust Policy 조건 검증:
    │  - aud: "sts.amazonaws.com" ✓
    │  - sub: "repo:hskhsmm/1milion-campaign-orchestration-system:*" ✓
    ▼
임시 자격증명 발급 (AccessKeyId + SecretAccessKey + SessionToken, 최대 1시간)
    │
    ▼
ECR, S3, SSM, CodeDeploy 작업 수행
```

장기 Access Key가 없으므로 키 노출/탈취 리스크가 없습니다.
임시 자격증명은 1시간 후 자동 만료됩니다.

### IAM 최소 권한 원칙

GitHub Actions Role이 할 수 없는 것들:
- 다른 S3 버킷 접근 (배포 버킷 외)
- 다른 SSM 경로 읽기 (`/batch-kafka/prod/` 외)
- EC2 직접 조작 (인스턴스 생성/삭제)
- RDS 접근
- IAM 수정

할 수 있는 것만:
- ECR 이미지 push
- 배포 버킷 S3 업로드
- 배포 파라미터 SSM 읽기 + ECR_IMAGE 갱신
- CodeDeploy 배포 생성/모니터링

### 네트워크 보안 계층

```
퍼블릭 서브넷:  ALB (80/443 → 인터넷 공개)
               Kafka EC2 (9092 제한된 SG에서만)
               MCP EC2 (모니터링 서버)

프라이빗 서브넷: App ASG (8080, ALB SG에서만 → 직접 IP 접근 불가)

VPC 내부만:    RDS MySQL (3306, App SG에서만)
               ElastiCache (6379, App+MCP SG에서만)
```

App 인스턴스에 직접 접근 불가 — ALB를 통해서만 트래픽 수신.
RDS에 직접 접근 불가 — App을 통해서만.
SSH(22) 인바운드 없음 — SSM Session Manager로만 접속.

### SSH 차단 + SSM Session Manager

```bash
# EC2에 SSH 없이 접속
aws ssm start-session --target i-0abc123def456

# 포트 포워딩 (로컬 개발/디버깅용)
aws ssm start-session --target i-0abc123def456 \
  --document-name AWS-StartPortForwardingSession \
  --parameters '{"portNumber":["8080"],"localPortNumber":["8080"]}'
```

SSM Agent가 AWS 서비스 엔드포인트(443)로 아웃바운드 터널을 유지합니다.
인바운드 포트 불필요 → Security Group에 22 없음.
모든 세션이 CloudTrail에 자동 기록되어 감사 추적이 됩니다.

---

## 섹션 13 추가 Q&A

### Q. Virtual Thread를 선택한 이유는 무엇인가요? 기존 스레드 풀과 차이는?

**답변:**
기존 Tomcat 스레드 풀은 OS 스레드를 기반으로 하고, 스레드 1개가 메모리를 1~2MB 사용합니다.
동시 요청 수천 개를 처리하려면 수천 개의 OS 스레드가 필요하고, 컨텍스트 스위칭 비용이 큽니다.

Virtual Thread는 JVM이 관리하는 경량 스레드입니다.
수십만 개를 생성해도 메모리 오버헤드가 거의 없고, JVM 스케줄러가 블로킹 I/O 시 플랫폼 스레드를 자동으로 다른 Virtual Thread에 할당합니다.

이 시스템은 Redis → Kafka 순서로 I/O 바운드 작업이 많아 Virtual Thread 효과가 큽니다.
API 요청마다 새 Virtual Thread를 생성하고 Redis Lua 실행 결과를 기다리는 동안 플랫폼 스레드 점유가 없습니다.

---

### Q. GitHub Actions에서 AWS 접근 시 Access Key를 쓰지 않은 이유는?

**답변:**
Access Key는 장기 자격증명이라 한번 노출되면 만료 없이 악용될 수 있습니다.
GitHub Secrets에 저장해도 로그 노출, 레포 공개 전환 실수 등으로 유출 위험이 있습니다.

OIDC는 GitHub Actions 실행 시 JWT를 발급하고 AWS STS와 교환해 임시 자격증명(최대 1시간)만 사용합니다.
IAM Role Trust Policy에 레포 조건을 달아 다른 레포가 동일 Role을 assume하는 것을 차단합니다.
결과적으로 저장하거나 관리해야 할 비밀값이 없습니다.

---

### Q. Terraform에서 `ignore_changes`를 어디에 쓰셨고 왜 필요한가요?

**답변:**
두 곳에 사용했습니다.

**ASG capacity (`desired_capacity, min_size, max_size`)**
부하 테스트 시 콘솔에서 `desired=3`으로 수동 조정합니다.
`ignore_changes`가 없으면 `terraform apply` 시 코드 값(`desired=2`)으로 강제 롤백됩니다.
ASG 용량은 Target Tracking과 콘솔이 관리하고, Terraform은 코드 구조만 관리하도록 분리했습니다.

**RDS password**
DB 비밀번호는 SSM에서 직접 관리합니다.
`ignore_changes = [password]`가 없으면 Terraform이 `terraform.tfstate`와 비교해 비밀번호를 원복하려 합니다.
비밀번호 변경은 SSM에서만 하고, Terraform은 나머지 DB 설정만 관리합니다.

---

### Q. RDS multi_az=false인데 고가용성은 어떻게 보장하나요?

**답변:**
포트폴리오 환경이라 비용을 절감했습니다.
RDS가 다운되는 장애는 T06에서 테스트했는데, 핵심 발견은 **DB 다운이 API 경로에 영향을 주지 않는다**는 것입니다.

v3 Redis-first 구조에서 API 경로는 DB를 사용하지 않습니다.
RDS 장애 시 Consumer의 INSERT 실패 → ack 보류 → Kafka가 재전달하므로 메시지가 보존됩니다.
DB 복구 후 자동으로 재처리됩니다.

실제 운영 환경이라면 `multi_az=true`로 Primary 장애 시 Standby 자동 승격을 설정하겠지만,
현재 시스템 구조상 DB 다운이 데이터 손실로 이어지지 않습니다.

---

---

## 20. MCP 서버 — AI 자율 운영 레이어

### 전체 구조

```
terraform-mcp EC2 (t3.large)
├── Prometheus     :9090  — 메트릭 수집 (Spring Boot, kafka-exporter, redis-exporter)
├── Grafana        :3000  — 대시보드 시각화
├── redis-exporter :9121  — ElastiCache 메트릭 → Prometheus
├── kafka-exporter :9308  — Kafka 메트릭 → Prometheus
└── mcp-server     :8000  — FastAPI + APScheduler + MCP SDK (Python)

모두 Docker 컨테이너, monitoring 네트워크로 이름 기반 통신
(IP 변경 없이 컨테이너 이름으로 DNS 해결)
```

**설계 원칙: AI는 탐지와 설명만, 실행은 사람이 판단**

Grafana 대시보드를 사람이 직접 보던 수동 감시를 자동화했습니다.
MCP 서버는 30초마다 Prometheus/CloudWatch를 폴링해 이상을 탐지하고 Slack으로 알립니다.
Claude는 MCP 도구 7개를 통해 운영 중 즉시 쿼리할 수 있지만,
실제 조치(스케일아웃, 재시작 등)는 사람이 판단해 실행합니다.

### main.py — FastAPI + APScheduler 기동

```python
# main.py
@asynccontextmanager
async def lifespan(app: FastAPI):
    scheduler.add_job(run_monitor, "interval", seconds=30, id="monitor")
    scheduler.add_job(run_consistency_check, "interval", hours=1, id="consistency")
    scheduler.start()
    send_alert("OK", "모니터링 시작", "MCP 모니터링 서버가 정상 기동되었습니다.")
    yield
    scheduler.shutdown(wait=False)

app = FastAPI(title="MCP Monitor Server", routes=mcp_routes, lifespan=lifespan)

@app.put("/config/campaign")
def update_campaign(body: CampaignConfig) -> dict:
    config.BATCH_CAMPAIGN_ID = body.campaign_id   # 재시작 없이 런타임 변경
    return {"campaign_id": config.BATCH_CAMPAIGN_ID}
```

**`/config/campaign` 런타임 변경**

캠페인 ID가 테스트마다 바뀝니다.
컨테이너 재시작 없이 PUT 요청 한 번으로 BATCH_CAMPAIGN_ID를 변경해 정합성 검사 대상을 갱신합니다.

**APScheduler 두 개 잡**

| 잡 | 주기 | 역할 |
|----|------|------|
| `run_monitor` | 30초 | P1/P2/P3 전체 탐지 실행 |
| `run_consistency_check` | 1시간 | Redis ↔ DB 정합성 검사 |

### P1/P2/P3 — 우선순위별 탐지 계층

```python
# monitor.py
def run_monitor() -> None:
    for check in [check_p1, check_p2, check_p3]:
        try:
            check()
        except Exception as e:
            logger.error("%s 오류: %s", check.__name__, e)
```

각 탐지 함수가 실패해도 다음 탐지가 계속 실행됩니다.
P1 탐지 실패가 P2/P3를 막지 않습니다.

**P1 — 즉시 대응 필요 (비즈니스 영향)**

| 탐지 항목 | 임계값 | Prometheus 쿼리 |
|---------|--------|----------------|
| 5xx 에러 | 1건 초과 / 30초 | `sum(increase(http_server_requests_seconds_count{status=~"5.."}[30s]))` |
| Redis Queue WARNING | 1,050,000 (70%) | `max by (campaignId) (redis_queue_size)` |
| Redis Queue CRITICAL | 1,275,000 (85%) | 동일 (임계값 다름) |
| 데이터 정합성 불일치 | diff > 0 | 배치 API `/consistency` 호출 |

**P2 — 성능 저하 경고**

| 탐지 항목 | WARNING | CRITICAL | 데이터 소스 |
|---------|---------|---------|-----------|
| 앱 CPU | 80% | 90% | CloudWatch ASG |
| Kafka lag | 500 | 1,000 | Prometheus kafka-exporter |
| Consumer 지연 | 50ms | 200ms | Prometheus (커스텀 메트릭) |
| HikariCP pending | 1개 이상 | — | Prometheus |

**P3 — 인프라 관찰 (즉각 대응 불필요)**

| 탐지 항목 | 임계값 | 데이터 소스 |
|---------|--------|-----------|
| RDS CPU | 60% | CloudWatch RDS |
| Bridge 드레인 사이클 지연 | 60초 | Prometheus (커스텀 메트릭) |

**임계값 설계 근거**

```python
# config.py
REDIS_QUEUE_WARNING  = 1_050_000  # MAX_QUEUE_SIZE(1.5M)의 70% — 여유 30%
REDIS_QUEUE_CRITICAL = 1_275_000  # 85% — 유실 위험 임박
HTTP_5XX_THRESHOLD   = 1          # 1건이라도 발생하면 즉시 알림
KAFKA_LAG_CRITICAL   = 1_000      # 14차 테스트 최대 lag ~500 → 2배 여유
CONSUMER_LATENCY_CRITICAL_MS = 200  # batchUpdate 이후 정상 7~20ms → 10배 여유
HIKARI_PENDING_THRESHOLD = 1      # v3에서 0이 정상 → 1개라도 발생 시 이상
COOLDOWN_SECONDS = 300            # 5분 — 동일 알림 반복 발송 방지
```

### state.py — 쿨다운 메커니즘

```python
# state.py
_alert_state: dict[str, float] = {}
_lock = threading.Lock()

def check_and_record(key: str, cooldown: int) -> bool:
    """can_alert + record_alert를 atomic하게 실행. True면 알림 발송 가능."""
    with _lock:
        if (time.time() - _alert_state.get(key, 0)) >= cooldown:
            _alert_state[key] = time.time()
            return True
        return False   # 쿨다운 중 → 알림 스킵
```

30초마다 탐지가 실행되므로 쿨다운 없이 두면 5분 동안 10번의 동일 알림이 발송됩니다.
`check_and_record`는 동일 `key`에 대해 5분(COOLDOWN_SECONDS) 안에 한 번만 True를 반환합니다.
`threading.Lock`으로 멀티스레드 환경에서 동시 알림 발송을 차단합니다.

이상이 해소되면 `reset_alert(key)`로 쿨다운을 초기화합니다.
다음 사이클에서 같은 이상이 재발하면 즉시 새 알림이 발송됩니다.

### MCP 도구 7개

```python
# tools.py — Claude가 직접 호출할 수 있는 읽기 전용 운영 도구
```

| 도구 | 용도 | 입력 |
|------|------|------|
| `get_monitor_status` | 현재 활성 alert 쿨다운 상태 조회 | 없음 |
| `run_check` | P1/P2/P3 detector 수동 즉시 실행 + Slack 발송 | `level: p1\|p2\|p3\|all` |
| `query_prometheus` | PromQL instant query 실행 | `promql` |
| `query_prometheus_range` | 특정 시간 구간 메트릭 조회 (min/max/avg) | `promql, start_minutes_ago, end_minutes_ago` |
| `get_test_report` | 테스트 구간 전체 핵심 메트릭 한 번에 요약 | `start_minutes_ago` |
| `reset_cooldown` | 특정 alert 쿨다운 초기화 (`all`로 전체 가능) | `key` |
| `trigger_consistency_check` | Redis ↔ DB 정합성 검사 즉시 실행 | 없음 |

**MCP SSE Transport**

```python
# tools.py 하단
sse = SseServerTransport("/mcp/messages")

mcp_routes = [
    Route("/mcp/sse",      endpoint=handle_sse,      methods=["GET"]),
    Route("/mcp/messages", endpoint=handle_messages, methods=["POST"]),
]
```

Claude Desktop이 SSE(Server-Sent Events) 방식으로 MCP 서버에 연결합니다.
FastAPI에 MCP 라우트를 직접 마운트해 별도 서버 없이 단일 포트(8000)로 운영합니다.

### get_test_report — 테스트 구간 자동 분석

```python
# tools.py
_REPORT_METRICS = [
    ('sum(increase(http_server_requests_seconds_count{status=~"5.."}[1m]))', "5xx 에러",   config.HTTP_5XX_THRESHOLD,   "건"),
    ("max by (campaignId) (redis_queue_size)",                                "Redis Queue", config.REDIS_QUEUE_CRITICAL, "건"),
    ("sum(kafka_consumergroup_lag_sum{...})",                                 "Kafka lag",   config.KAFKA_LAG_CRITICAL,   ""),
    ("consumer_pending_to_success_latency_seconds_max * 1000",               "Consumer 지연", config.CONSUMER_LATENCY_CRITICAL_MS, "ms"),
    ("max(hikaricp_pending_threads)",                                         "HikariCP",    config.HIKARI_PENDING_THRESHOLD, "개"),
    ("bridge_drain_duration_seconds_max",                                     "Bridge 사이클", config.BRIDGE_CYCLE_WARNING_SECONDS, "초"),
]
```

`get_test_report(start_minutes_ago=60)` 호출 시 위 6개 메트릭을 한 번에 조회해
각각 최대값과 임계값 초과 여부를 표 형태로 반환합니다.
테스트 종료 후 "어디서 문제가 있었나"를 한 번의 도구 호출로 파악합니다.

### 12차 테스트 실시간 검증

12차 테스트(130만 재고, 150만 요청, ASG 3대) 실행 중 MCP 서버가 실제로 동작했습니다.

- GC 이상(P2) 탐지 → Slack 알림
- Kafka lag 스파이크(P2) 탐지 → Slack 알림
- 테스트 종료 후 `trigger_consistency_check` 실행 → diff 141,062 발견 → P1 정합성 불일치 알림

이 흐름이 MCP 서버의 실제 운영 검증이었습니다.
단, 조치(MAX_QUEUE_SIZE 상향, Lua 원자화)는 사람이 직접 분석하고 적용했습니다.

---

### Q. MCP 서버에서 AI가 직접 스케일아웃하거나 재시작하는 기능은 없나요?

**답변:**
의도적으로 읽기 전용으로 설계했습니다.

AI가 오탐(false positive)으로 인스턴스를 줄이거나 서비스를 재시작하면 더 큰 장애가 발생할 수 있습니다.
AI는 탐지 → 설명 → 운영자에게 알림까지만 담당하고,
실제 조치는 사람이 상황을 판단해 실행합니다.

MCP 도구 7개 모두 조회/탐지/알림만 합니다.
실제 인프라 변경(ASG 조정, 컨테이너 재시작 등)은 도구에 포함돼 있지 않습니다.

---

### Q. Prometheus와 CloudWatch를 둘 다 쓰는 이유는 무엇인가요?

**답변:**
두 시스템이 커버하는 메트릭의 종류가 다릅니다.

Prometheus는 Spring Boot Actuator, kafka-exporter, redis-exporter에서
**애플리케이션 레벨** 메트릭을 수집합니다.
(http.server.requests, Kafka lag, Redis Queue 크기, HikariCP pending 등)

CloudWatch는 AWS 관리형 서비스의 **인프라 레벨** 메트릭을 제공합니다.
(EC2 ASG CPU, RDS CPU — EC2에 에이전트 없이 AWS가 자동 수집)

ASG CPU를 Prometheus로 수집하려면 각 EC2에 node_exporter를 설치해야 합니다.
CloudWatch는 별도 설치 없이 ASG 전체 평균을 제공하므로 이 용도에 더 적합합니다.
Micrometer CloudWatch Export로 커스텀 메트릭도 CloudWatch에 Push해 양쪽에서 동일한 메트릭을 볼 수 있습니다.

---

*작성일: 2026-05-11*
