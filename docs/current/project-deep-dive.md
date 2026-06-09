# 1Million Campaign Orchestration System — 프로젝트 심층 분석

> 이 문서는 프로젝트를 구축하면서 생긴 궁금증과 그에 대한 답변을 정리한 문서입니다.
> 면접 준비 및 프로젝트 이해 심화 목적으로 작성되었습니다.

---

## 목차

1. [전체 아키텍처 흐름](#1-전체-아키텍처-흐름)
2. [Redis 역할과 원리](#2-redis-역할과-원리)
3. [번호표(sequence) 프로토콜](#3-번호표sequence-프로토콜)
4. [Kafka 역할과 원리](#4-kafka-역할과-원리)
5. [Producer / Consumer 동작](#5-producer--consumer-동작)
6. [DLQ 사용 방식](#6-dlq-사용-방식)
7. [각 컴포넌트 연결 프로토콜](#7-각-컴포넌트-연결-프로토콜)
8. [Redis 운영 이슈](#8-redis-운영-이슈)

---

## 1. 전체 아키텍처 흐름

### 한 사람의 참여 요청 전체 경로

```
User HTTP POST
  → ALB (L7 로드밸런서)
    → ASG 앱 인스턴스 중 하나 (라운드로빈)
      → RateLimitService (SET NX EX 10, 중복 방지)
      → Redis Lua 원자 실행 (재고차감 + 큐적재)
      → 202 즉시 반환  ← 여기서 HTTP 응답 끝, DB 미접촉

      (별도 스레드, 100ms마다)
      ParticipationBridge
        → Redis RPOP
          → Kafka Produce
            → Kafka Consumer (앱 내부)
              → MySQL batchUpdate INSERT
```

### 앱 인스턴스 내부 구조

Spring Boot JAR 하나가 세 가지 역할을 동시에 수행합니다.

```
batch-kafka-app (JAR 하나)
  ├── HTTP 서버 (8080 포트)            ← 요청 받는 역할
  ├── ParticipationBridge (@Scheduled) ← Redis → Kafka 이동 역할
  └── ParticipationEventConsumer       ← Kafka → DB 저장 역할
```

별도 서버가 아니라 같은 프로세스 안에서 스레드만 나뉩니다.

### DB의 역할

DB가 역할이 없는 게 아니라 **응답 경로에서만 제외**된 것입니다.

- Redis: "일단 번호표 발급 + 큐 적재" (빠른 접수)
- MySQL: "최종 결과 기록" (영구 저장)

14차 테스트에서 `SELECT COUNT(*) = 1,500,000` 으로 정합성을 검증한 것도 DB 쿼리였습니다.
Redis는 껐다 켜면 데이터가 날아갈 수 있지만, DB는 영구 저장이라 **정합성의 최종 근거**가 DB입니다.

### MCP 서버의 역할

MCP 서버가 수백만 요청을 보내는 게 아닙니다.

```
실제 부하 발생: k6 (stress-test/) → ALB → 앱
MCP 서버 역할: Prometheus/CloudWatch 30초 폴링 → 이상 탐지 → Slack 알림
              (감시자 역할, 부하 발생 주체 아님)
```

### Prometheus 메트릭 수집 방식

Pull 방식입니다. 앱이 보내는 게 아니라 Prometheus가 긁어옵니다.

```
terraform-mcp (Prometheus)
  → 30초마다 HTTP GET http://앱:8080/actuator/prometheus   (Micrometer)
  → 30초마다 HTTP GET http://redis-exporter:9121/metrics
  → 30초마다 HTTP GET http://kafka-exporter:9308/metrics
```

ec2_sd_configs 방식으로 AWS EC2 API를 호출해 ASG 인스턴스 목록을 자동 발견합니다.
인스턴스가 terminate/새로 뜨면 자동 반영됩니다.

---

## 2. Redis 역할과 원리

### Redis를 쓰는 근본 원리

선착순 시스템의 핵심 문제:

```
100만 명이 동시에 "내가 먼저야" 라고 주장
  → 서버가 단 1명만 "맞아, 네가 1번이야" 라고 확정해야 함
  → 이 확정이 중복 없이, 순서대로, 빠르게 이루어져야 함
```

이를 보장하려면 **모든 요청이 반드시 한 줄로 서는 지점**이 필요합니다.

Redis 싱글스레드 = 물리적으로 동시 실행 불가능

```
DECR stock

요청 A, B, C가 동시에 도착해도:
  A: stock 1,000,000 → 999,999  (확정)
  B: stock 999,999   → 999,998  (확정)
  C: stock 999,998   → 999,997  (확정)

중복 없음, Lock 없음, 순서 보장
```

**"한 줄 서기"를 Lock이 아니라 아키텍처 자체로 해결한 것**

### 다른 방식과 비교

| 방식 | 가능? | 문제 |
|------|-------|------|
| MySQL Row Lock | 가능 | Lock 대기 → TPS 한계 (HikariCP pending 980) |
| 분산 락 (Zookeeper) | 가능 | 네트워크 왕복 2회 이상 → 느림 |
| AtomicLong (JVM) | 가능 | ASG 3대 → 각자 다른 JVM → 공유 안 됨 |
| **Redis DECR** | 가능 | **빠름 + 수평 확장 가능** |

### 싱글스레드 vs Lua 스크립트

DECR 하나만 쓸 때는 싱글스레드로 충분하지만, 이 프로젝트는 **5개 연산을 묶어야 했습니다.**

```
DECR         ← 싱글스레드로 원자
LPUSH        ← 싱글스레드로 원자
but DECR → LPUSH 사이 = 두 개의 독립 명령
  → 그 사이에 다른 클라이언트 끼어들 수 있음
```

Lua 스크립트는 **스크립트 전체가 하나의 명령처럼** 처리됩니다.

```
12차 버그가 바로 이것:
  DECR(성공) → [다른 클라이언트 끼어듦] → LPUSH(Queue full, 실패)
  → 재고는 차감됐는데 큐엔 없음 → 202 반환 → DB에 기록 없음 → 복구 불가
```

### Redis 싱글스레드 vs Kafka 병렬처리 (공존 이유)

핵심은 **"무엇을 직렬화해야 하는가"** 입니다.

```
[직렬화 구간]          [병렬화 구간]
Redis DECR (1스레드) → Kafka 10파티션 → Consumer 10스레드 → MySQL
     ↑                      ↑
  정합성 보장              성능 확보
```

- Redis: 재고 차감 (누가 몇 번인지 확정) → 반드시 직렬화
- Kafka: DB 저장 (이미 번호 확정된 것을 기록) → 병렬로 써도 정합성 안 깨짐

은행 번호표 비유:
```
번호표 기계 (Redis)  → 반드시 한 명씩 순서대로 발급
창구 (Consumer)      → 번호표 받은 사람들을 여러 창구에서 동시 처리
                       1번이 3번보다 나중에 처리돼도 번호표 자체는 안 바뀜
```

### Redis는 묶어서 보내지 않는다

Redis 큐는 1건씩 넣고 꺼냅니다. 묶음 처리는 Consumer → MySQL 구간에서만 발생합니다.

| 구간 | 방식 |
|------|------|
| API → Redis 큐 | 1건씩 LPUSH |
| Bridge → Kafka | 1건씩 produce (batchSize번 반복) |
| **Consumer → MySQL** | **batchUpdate로 묶어서 INSERT** |

### Redis 버퍼 기능 (Kafka 없다고 가정)

| 기능 | 설명 |
|------|------|
| 속도 차이 흡수 | API 3,737건/s vs MySQL 수백건/s → 큐에 쌓아두고 MySQL 속도에 맞춰 꺼냄 |
| 트래픽 스파이크 평탄화 | 피크 4,800/s 구간을 큐에 쌓아 DB 입장에서 평탄화 |
| 장애 격리 | DB 다운 시에도 API는 Redis까지만 → 202 정상 응답, 사용자는 장애 모름 |

Kafka 없이 Redis만 있으면 "API → DB 직격 방지"는 되지만, DB 장애 시 RPOP한 메시지 유실 → 재처리 로직 직접 구현 필요합니다.

### MAX_QUEUE_SIZE를 무턱대고 높이면 안 되는 이유

**RAM 한도:**
```
queue:campaign:{id} 메시지 1건 ≈ 약 80~100 bytes
150만 건 × 100 bytes = 150MB

cache.t3.micro 1대 메모리 = 0.5GB
큐 외에도 stock, total, active, participated 키 전부 점유 중
→ 무턱대고 올리면 OOM → Redis 강제 eviction → 데이터 유실
```

**적정값:** 재고 수 × 1.2~1.5배 (인스턴스 메모리 한도 안에서)

| 차수 | MAX_QUEUE_SIZE | 결과 |
|------|--------------|------|
| 10차 | 500K | 425K 유실 |
| 11차 | 1M | 100만 정합성 완벽 |
| 12차 | 1M | 141K 유실 (130만 재고) |
| 13차 | 1.5M | 150만 정합성 완벽 |

---

## 3. 번호표(sequence) 프로토콜

별도 프로토콜이 없습니다. Lua 스크립트 안에서 계산 후 JSON에 포함해 큐에 적재됩니다.

### 발급 공식

```lua
-- check-decr-enqueue.lua:27-28
local total = tonumber(redis.call('GET', KEYS[3])) or 0
local sequence = total - remaining   -- ex) 1,500,000 - 999,999 = 500,001
local message = '{"campaignId":' .. ARGV[2] .. ',"userId":' .. ARGV[3] .. ',"sequence":' .. sequence .. '}'
redis.call('LPUSH', KEYS[4], message)
```

- remaining이 1,499,999 → sequence=1 (첫 번째 참여자)
- remaining이 0 → sequence=1,500,000 (마지막 참여자)

### 전체 흐름

```
Lua 원자 실행
  DECR → remaining = 999,999
  GET total → 1,500,000
  sequence = 1,500,000 - 999,999 = 500,001
  LPUSH queue → {"campaignId":5, "userId":123, "sequence":500001}
         ↓
ParticipationBridge (100ms 스케줄)
  RPOP → JSON 문자열 그대로 Kafka publish (파싱 없이 통과)
         ↓
ParticipationEventConsumer.parseRecords()
  jsonMapper.readValue → ParticipationEvent 역직렬화
  event.getSequence() == null 체크 (없으면 DLQ)
         ↓
jdbcTemplate.batchUpdate
  INSERT INTO participation_history (campaign_id, user_id, sequence, status, created_at)
```

### DB에서의 역할

DB는 sequence를 **해석하지 않고 저장만** 합니다. `INSERT IGNORE`로 멱등성 처리에 활용됩니다.

| 용도 | 위치 |
|------|------|
| 선착순 순번 기록 | `participation_history.sequence` 컬럼 |
| DLQ 재처리 시 원본 보존 | `DlqEventPayload.sequence` |
| PENDING 복구 시 sequence 유지 | `PendingRecoveryJobConfig` |

---

## 4. Kafka 역할과 원리

### Kafka = 내구성 있는 중계 서버

```
Redis 큐 (빠른 접수)
    ↓
  Kafka  ← 중계 (메시지를 디스크에 보관)
    ↓
  MySQL (느린 저장)
```

일반 HTTP 프록시와 차이:
```
일반 중계: 전달 실패 → 메시지 사라짐
Kafka:    메시지를 디스크에 저장 → Consumer가 가져갈 때까지 보관
          실패해도 offset 되감아서 재전달
```

### Kafka가 필요한 이유

**핵심 이유: MySQL 장애 시 메시지 유실 방지**

```
Kafka 없으면:
  RPOP 500건 꺼냄 → MySQL INSERT 시도
  → MySQL 다운 → 500건 어디에 보관?
  → Redis에서 이미 꺼낸 상태 → 재적재 하면 순서/중복 틀어짐

Kafka 있으면:
  RPOP → Kafka produce (성공)
  → Consumer INSERT 실패 → ack 안 보냄
  → Kafka가 offset 유지 → 자동 재전달
  → MySQL 복구 후 자동 처리
```

T06에서 RDS SG 3306 삭제 후 복구 시 자동 재처리된 게 이 덕분입니다.

### Kafka 없어도 가능하지만

| 이유 | Kafka 없이 가능? |
|------|----------------|
| MySQL 장애 시 메시지 유실 방지 | 직접 구현 필요 |
| 파티션 병렬 처리 | Redis 멀티 큐로 대체 가능 |
| DLQ / 재처리 | 직접 구현 필요 |
| offset 기반 재처리 | 직접 구현 필요 |

> 면접 답변: "왜 Kafka를 썼나요?" → **"MySQL 장애 격리와 메시지 유실 방지"** 가 첫 번째 이유

### 파티션이 여러 개인 이유

```
파티션 1개, Consumer 1개면:
  Redis가 초당 3,000건 처리
  → Kafka에 3,000건 쌓임
  → Consumer 1개가 200건/s 처리
  → Kafka lag 계속 누적 → 감당 불가

파티션 10개, Consumer 10개면:
  각 Consumer가 300건/s씩 담당
  → 합산 3,000건/s 처리 가능
```

6차 테스트에서 campaignId를 파티션 키로 썼을 때 한 파티션에 몰리는 문제 발견 → userId로 변경했습니다.

### Bridge의 파티션 키 (userId)

```java
// 파티션 키 = userId
kafkaTemplate.send(TOPIC, String.valueOf(event.getUserId()), message)
```

```
userId=123 → hash → 파티션 3  (항상 같은 파티션)
userId=456 → hash → 파티션 7
```

같은 유저의 메시지가 항상 같은 파티션 → 같은 Consumer가 순서대로 처리 → `INSERT IGNORE`로 중복 방지

---

## 5. Producer / Consumer 동작

### Producer (ParticipationBridge)

```java
@Scheduled(fixedDelay = 100)  // 100ms마다 실행
drainQueues()
  → SMEMBERS active:campaigns
  → 각 캠페인마다 RPOP × batchSize
  → kafkaTemplate.send(topic, userId, message)  // 비동기
      .whenComplete((result, ex) -> {
          if (ex != null) → DLQ
          else → 카운터 증가
      });
```

동적 batchSize:
```
queueSize < 10,000   → 500건
queueSize < 100,000  → 1,000건
queueSize >= 100,000 → 2,000건
```

### Consumer (ParticipationEventConsumer)

```java
@KafkaListener(
    topics = "campaign-participation-topic",
    concurrency = 10  // 스레드 10개, 파티션 10개에 1:1 매핑
)
public void consumeParticipationEvent(
    List<ConsumerRecord<String, String>> records,  // 배치로 받음
    Acknowledgment acknowledgment
)
```

```
파티션 0  → Consumer 스레드 0
파티션 1  → Consumer 스레드 1
...
파티션 9  → Consumer 스레드 9
```

ack 처리:
```
INSERT 성공 → acknowledgment.acknowledge() → offset 커밋
INSERT 실패 (RDS 다운 등) → ack 보류 → Kafka가 재전달
```

### Kafka → DB 경로에서 서비스 로직을 거치지 않는 이유

Consumer는 `ParticipationService`를 거치지 않고 DB에 직접 씁니다.

```
서비스 로직(ParticipationService)이 하는 일:
  1. RateLimitService 체크
  2. Redis Lua 실행 (재고 차감 + 큐 적재)
  3. 202 반환

→ Consumer 입장에서는 이미 다 끝난 일
→ 재고 차감, 번호표, 중복 체크 모두 Redis에서 완료
→ Consumer는 "확정된 결과를 DB에 기록"만 하면 됨
```

Consumer는 서비스 레이어가 아니라 **인프라 레이어** 역할입니다.

---

## 6. DLQ 사용 방식

### DLQ란

Dead Letter Queue — 정상 처리 실패한 메시지를 버리지 않고 별도로 보관하는 큐입니다.

```
정상 경로: Redis → Kafka 메인토픽 → Consumer → MySQL
DLQ 경로:  처리 실패 → campaign-participation-topic.dlq 토픽 + DB 저장
```

### DLQ가 발동되는 3가지 지점

**1. Bridge에서 Kafka produce 실패**
```
Redis RPOP 성공 → kafkaTemplate.send() 실패
  → 메시지가 Redis에서 이미 꺼낸 상태 → 그냥 버리면 유실
  → DLQ로 이동
```
- `IMMEDIATE_SEND_FAILED`: send() 호출 자체가 예외
- `ASYNC_SEND_FAILED`: 비동기 콜백에서 실패

**2. Consumer에서 파싱/INSERT 실패**
```
sequence가 null          → DLQ ("MISSING_SEQUENCE")
JSON 자체가 깨짐          → DLQ ("JSON_PARSE_ERROR")
RDS 다운 등 INSERT 실패   → DLQ ("INSERT_FAILED") + ack 보류
```

**3. DLQ 발동 시 동시에 두 가지**
```java
// DlqMessageService.publishAndStore()
saveDlqMessage(payload);   // MySQL dlq_message_record 테이블 저장
publishDlqEvent(payload);  // Kafka DLQ 토픽으로 publish
```

### DLQ 재처리 (Replay)

```
운영자 → POST /api/dlq/replay
  → DB에서 PENDING 상태 DLQ 메시지 조회
    → PolicyService 판단:
```

| 판단 | 조건 | 행동 |
|------|------|------|
| REPLAY | campaignId/userId/sequence 있고, DB에 SUCCESS 없음 | 메인 Kafka 토픽에 재publish |
| SKIP | DB에 이미 SUCCESS 기록 있음 | 건너뜀 |
| FINAL_FAIL | JSON 파싱 불가, sequence 없음 | 복구 불가로 확정 |

### T06에서 실제 동작

```
RDS SG 3306 삭제 (장애 주입)
  → Consumer INSERT 실패 → ack 보류
  → Kafka가 메시지 재전달 대기

SG 복구
  → Kafka 자동 재전달 → Consumer INSERT 성공
  (DLQ까지 안 가고 ack 보류 경로로 자동 해소)
```

DLQ는 ack 보류보다 더 심각한 실패를 위한 **마지막 안전망**입니다.

---

## 7. 각 컴포넌트 연결 프로토콜

HTTP가 아닙니다. 각각 전용 프로토콜을 사용합니다.

| 연결 | 프로토콜 | 포트 |
|------|----------|------|
| Spring Boot → Redis | RESP (Redis Serialization Protocol) | 6379 |
| Spring Boot → Kafka | Kafka 바이너리 프로토콜 (TCP) | 9092 |
| Spring Boot → MySQL | MySQL 프로토콜 (TCP) | 3306 |
| 클라이언트 → Spring Boot | HTTP/1.1 | 8080 |

HTTP가 아닌 이유: HTTP는 요청-응답 한 쌍으로 끝나는 무상태 프로토콜입니다.
Redis/Kafka/DB는 **커넥션을 유지하고 재사용**하는 게 핵심이라 각자 전용 프로토콜을 씁니다.

이것이 HikariCP 병목의 근본 원인이었습니다:
```
HTTP 요청 1개마다 MySQL 커넥션 1개 점유
→ INSERT 완료될 때까지 블로킹
→ 동시 요청 많으면 풀 고갈 → pending 980
→ v3에서 API 응답 경로를 Redis로 옮겨 해결
```

---

## 8. Redis 운영 이슈

### ElastiCache replica의 실제 역할

코드 확인 결과 `readFrom: REPLICA` 설정이 없습니다. 모든 읽기/쓰기가 primary로만 갑니다.

- **replica 목적**: HA (고가용성) — primary 장애 시 자동 승격
- **부하 분산 방법**: 샤딩 (3 shards)

```
ElastiCache CME 3샤드:
  샤드 1: 해시슬롯 0~5460
  샤드 2: 해시슬롯 5461~10922
  샤드 3: 해시슬롯 10923~16383

→ 요청이 3개 primary에 분산 → 단일 Redis 대비 처리량 3배
```

| 목적 | 수단 |
|------|------|
| 부하 분산 | 샤딩 (3 shards) |
| 장애 복구 | replica (자동 승격) |
| 멀티키 원자 연산 | 해시태그 `{campaignId}` |

### replica 복제 부담

복제 부담은 이론상 존재하지만 실측 성능에 거의 영향 없습니다.

- **비동기 복제**: primary는 클라이언트에 즉시 응답, 백그라운드로 replica 전송
- **명령어 전달 방식**: 실제 데이터 전체가 아닌 명령어 자체를 복제
- **VPC 내부 통신**: 레이턴시 1ms 미만

14차 테스트에서 Redis가 병목이 아니라 **앱 CPU 97%** 가 병목이었던 이유입니다.

### Redis 메모리 뻑났던 원인

백업 설정 확인 결과 `snapshot_retention_limit`이 없습니다. ElastiCache 기본값 = 0 (자동 백업 비활성화).

따라서 RDB 스냅샷 백업 문제가 아닌 다른 원인들:

**가능성 1. participated 키 폭발**
```
participated:campaign:{id}:user:{userId}
→ 참여자 수만큼 키 생성 (TTL 없음)
→ 150만 건 = 150만 개 키 → 메모리 누적
```

**가능성 2. 테스트 반복으로 누적**
```
여러 차수 테스트하면서 이전 캠페인 키들이 DEL 안 되고 남아있음
→ 어느 순간 한도 초과
```

**대응**: FLUSHALL 또는 특정 캠페인 키 수동 DEL로 초기화

### Redis 메모리 확인 방법

```bash
# Redis CLI 직접
redis-cli -h <endpoint> INFO memory

# 큐 크기 확인
redis-cli -h <endpoint> LLEN queue:campaign:{id}

# Grafana PromQL
redis_memory_used_bytes / redis_memory_max_bytes * 100
redis_llen{key="queue:campaign:{5}"}
```

---

## 면접 핵심 정리

### "Redis를 왜 썼나요?"
> "싱글스레드로 모든 요청을 직렬화하면서도 마이크로초 단위로 응답하기 때문입니다. Lock 없이 정합성을 보장하면서 수평 확장도 되는 구조입니다."

### "Kafka를 왜 썼나요?"
> "MySQL 장애 격리와 메시지 유실 방지가 핵심입니다. Bridge가 Redis에서 꺼낸 메시지를 Kafka에 보관하면, MySQL이 다운돼도 ack 보류로 자동 재전달이 가능합니다. T06에서 실제 검증했습니다."

### "Kafka 파티션을 왜 10개로 했나요?"
> "Redis가 초당 3,000건 처리하는데 Consumer가 1개면 감당 불가입니다. 파티션 10개로 나눠 Consumer 10스레드가 병렬 처리합니다. 6차 테스트에서 Consumer 지연 1.25s → 200ms 개선을 확인했습니다."

### "sequence는 어떻게 보장하나요?"
> "Redis DECR이 싱글스레드로 순서를 확정하고, Lua 스크립트 안에서 total - remaining으로 계산합니다. 이 값이 JSON에 포함되어 Kafka를 통해 DB까지 전달됩니다."
