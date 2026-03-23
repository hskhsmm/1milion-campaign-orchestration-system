# 100만 트래픽 & AI 오케스트레이션 — 설계 가이드라인 v2

> 작성 기준: 현재 `event-driven-batch-kafka-system` (10만 트래픽) 코드베이스를 기반으로
> 새 레포지토리(`1milion-campaign-orchestration-system`)에서 고도화하는 전체 설계 문서입니다.

---

## 목차

1. [핵심 목표 및 설계 철학](#1-핵심-목표-및-설계-철학)
2. [레포지토리 구조](#2-레포지토리-구조)
3. [전체 아키텍처 흐름](#3-전체-아키텍처-흐름)
4. [Phase 1 — 인프라 코드화 (Terraform + Bastion)](#4-phase-1--인프라-코드화-terraform--bastion)
5. [Phase 2 — Redis 고도화](#5-phase-2--redis-고도화)
6. [Phase 2 — Kafka 고도화](#6-phase-2--kafka-고도화)
7. [Phase 2 — Spring Boot 코드 고도화](#7-phase-2--spring-boot-코드-고도화)
8. [Phase 2 — DB 스키마](#8-phase-2--db-스키마)
9. [Phase 3 — MCP 서버 (Python/FastAPI)](#9-phase-3--mcp-서버-pythonfastapi)
10. [Phase 3 — AI 오케스트레이션 파이프라인](#10-phase-3--ai-오케스트레이션-파이프라인)
11. [Phase 3 — Slack 승인 + DynamoDB 감사 로그](#11-phase-3--slack-승인--dynamodb-감사-로그)
12. [장애 대응 매트릭스](#12-장애-대응-매트릭스)
13. [미확정 파라미터 기준값](#13-미확정-파라미터-기준값)

---

## 1. 핵심 목표 및 설계 철학

### 목표

| 항목 | 기존 (v1) | 목표 (v2) |
|---|---|---|
| 트래픽 | 10만 건 | **100만 건** |
| 초과 판매 | 0건 | **0건 (동일)** |
| 앱 배포 | EC2 + CodeDeploy | **EC2 + CodeDeploy (동일, Terraform 관리)** |
| Kafka | EC2 단일 브로커 | **EC2 3-broker 클러스터** |
| Redis | ElastiCache 단일 | **ElastiCache Cluster (3샤드)** |
| 인프라 관리 | 수동 콘솔 | **Terraform IaC** |
| 운영 자동화 | 없음 | **AI(MCP) 자율 운영** |

### 설계 철학

```
Redis   → 빠른 컷 + 스파이크 방지 내부 버퍼 (성능의 원천)
Kafka   → 영속 로그 (진실의 원천, 재처리 기준)
DB      → 최종 확정 (상태의 원천)
PENDING → 자리 선점 (장애 복구의 기준점)

원칙 1: "Kafka 로그가 없으면 성공으로 인정하지 않는다"
원칙 2: "롤백을 믿지 말고, 롤백이 필요 없는 구조로 설계한다"
원칙 3: "서버 도착 순서가 선착순 기준이다"
원칙 4: "대기열 없이 즉시 컷, 단순하고 안정적인 구조"
원칙 5: "AI는 판단하되, 고위험 실행은 사람이 승인한다"
```

### 핵심 설계 결정 — Sorted Set 대기열 vs Lua DECR 즉시 컷

> **Lua DECR 즉시 컷 방식을 선택합니다.**

Sorted Set 기반 대기열은 "몇 번째 대기 중" UI를 보여줄 수 있지만,
100만 명이 동시에 ZADD → ZRANK를 호출하면 Redis 단일 키에 1M ops가 집중되어
오히려 병목이 됩니다.

Lua DECR 즉시 컷은 Redis 단일 원자 연산으로 재고를 컷하고,
초과분은 즉시 400으로 튕겨내어 DB에 도달하는 요청을 재고 수만큼으로 제한합니다.
이것이 100만 트래픽에 현실적으로 적합한 구조입니다.

---

## 2. 레포지토리 구조

```
1milion-campaign-orchestration-system/
│
├── app/                          # Spring Boot (Java 25)
│   ├── src/
│   ├── pom.xml
│   └── Dockerfile
│
├── infra/                        # Terraform (HCL)
│   ├── environments/
│   │   ├── prod/
│   │   │   ├── main.tf
│   │   │   ├── variables.tf
│   │   │   └── terraform.tfvars
│   │   └── dev/
│   ├── modules/
│   │   ├── vpc/
│   │   ├── ec2_app/
│   │   ├── ec2_kafka/
│   │   ├── rds/
│   │   ├── elasticache/
│   │   └── bastion/
│   └── backend.tf                # S3 tfstate 설정
│
├── mcp-server/                   # Python FastAPI (AI 제어 서버)
│   ├── main.py
│   ├── tools/
│   │   ├── get_metrics.py
│   │   ├── scale_service.py
│   │   ├── get_kafka_lag.py
│   │   └── trigger_batch.py
│   ├── requirements.txt
│   └── Dockerfile
│
├── stress-test/                  # k6 (100만 트래픽 시나리오)
│   ├── 1m-participation.js
│   ├── polling-verify.js
│   └── config/
│
└── README.md
```

---

## 3. 전체 아키텍처 흐름

```
                        ┌─────────────────────────────────────────────┐
                        │              사용자 요청 (100만 건)            │
                        └─────────────────────┬───────────────────────┘
                                              │
                                              ▼
                        ┌─────────────────────────────────────────────┐
                        │   ALB (Application Load Balancer)            │
                        │   → ECS Fargate (Spring Boot, 오토스케일링)   │
                        └─────────────────────┬───────────────────────┘
                                              │
                              ┌───────────────▼───────────────┐
                              │   [1] Rate Limiter             │
                              │   Redis SET NX EX              │
                              │   중복/어뷰징 차단 → 429        │
                              └───────────────┬───────────────┘
                                              │ 통과
                              ┌───────────────▼───────────────┐
                              │   [2] Redis Lua DECR           │
                              │   원자적 재고 차감              │
                              │   DECR 반환값 = 선착순 순번     │
                              │   재고 없음 → 400              │
                              └───────────────┬───────────────┘
                                              │ 재고 있음
                              ┌───────────────▼───────────────┐
                              │   [3] DB PENDING 저장          │
                              │   자리 선점 + 복구 기준점       │
                              └───────────────┬───────────────┘
                                              │
                              ┌───────────────▼───────────────┐
                              │   [4] Redis LPUSH Queue        │
                              │   {campaignId, userId, id}     │
                              └───────────────┬───────────────┘
                                              │
                              ┌───────────────▼───────────────┐
                              │   [5] 202 반환                 │
                              │   "접수됐습니다"                │
                              └───────────────────────────────┘


                        ┌─────────────────────────────────────────────┐
                        │   [7] Bridge 컴포넌트 (@Scheduled)           │
                        │   Redis Queue LMPOP → Kafka 배치 발행        │
                        │   실패 시 → LPUSH 재적재                     │
                        └─────────────────────┬───────────────────────┘
                                              │
                        ┌─────────────────────▼───────────────────────┐
                        │   [8] Kafka (MSK, 10 partitions)             │
                        │   영속 로그 — 진실의 원천                     │
                        │   acks=all, replication=3                    │
                        └─────────────────────┬───────────────────────┘
                                              │
                        ┌─────────────────────▼───────────────────────┐
                        │   [9] Kafka Consumer (멀티 파티션)            │
                        │   PENDING → SUCCESS 확정                     │
                        │   중복 방어 (unique constraint)               │
                        │   Redis result 캐시 기록                     │
                        └─────────────────────────────────────────────┘


                        ┌─────────────────────────────────────────────┐
                        │   [10] 사용자 폴링                            │
                        │   GET /result → Redis 캐시 → DB fallback     │
                        └─────────────────────────────────────────────┘


                        ┌─────────────────────────────────────────────┐
                        │   [11] AI 오케스트레이터 (MCP 서버)           │
                        │   CloudWatch 메트릭 수집 → Claude 분석        │
                        │   → 자동 스케일링 or Slack 승인 요청          │
                        └─────────────────────────────────────────────┘
```

---

## 4. Phase 1 — 인프라 코드화 (Terraform + Bastion)

### 4-1. 왜 Terraform인가?

수동으로 생성한 인프라는 재현이 불가능합니다.
장애 시 동일한 환경을 새로 구성하는 데 수 시간이 걸립니다.
Terraform은 인프라 상태를 코드로 정의하여 `terraform apply` 한 번으로
동일한 환경을 언제든 재현 가능하게 합니다.

또한 S3에 tfstate를 저장하면 관리 서버(Bastion)가 항상 최신 인프라 상태를
인지하고, AI가 인프라 변경을 안전하게 수행할 수 있습니다.

### 4-2. 전체 AWS 아키텍처

```
Region: ap-northeast-2 (서울)

VPC (10.0.0.0/16)
├── Public Subnet (10.0.1.0/24, 10.0.2.0/24)
│   ├── ALB
│   ├── EC2 App 서버 (Spring Boot, t3.medium × 2)   ← CodeDeploy 배포
│   ├── EC2 Kafka Broker × 3 (t3.medium)            ← KRaft 클러스터
│   └── EC2 Bastion (MCP 서버, t3.small)
│
└── Private Subnet (10.0.10.0/24, 10.0.11.0/24)
    ├── RDS MySQL 8.0 (Multi-AZ)
    └── ElastiCache Redis Cluster (6 nodes)
```

### 4-3. 앱 배포: EC2 + CodeDeploy (v1과 동일 방식)

v1에서 검증된 배포 방식을 유지합니다.
변경점은 Terraform으로 EC2 인프라를 코드화하는 것입니다.

```
GitHub Actions
  → mvn package → Docker build → ECR push
  → S3 deploy.zip 업로드
  → CodeDeploy → EC2 (appspec.yml 기반 배포)
```

v1 대비 개선:
- Blue/Green 실제 적용 (v1에서는 미구현)
- Terraform으로 EC2 AMI, 보안그룹, ALB 코드화

### 4-4. 왜 EC2 Kafka 3-broker 클러스터인가?

v1의 단일 브로커는 브로커 다운 시 서비스 전체가 중단됩니다.
3-broker 클러스터는 replication-factor=3으로 브로커 1대가 다운돼도
나머지 2대에서 서비스가 계속됩니다.

MSK 대신 EC2 직접 운영을 선택하는 이유:
- MSK는 비용이 EC2 대비 3~5배 높음
- KRaft(Zookeeper 없이) 모드로 운영 복잡도 해결
- CloudWatch Agent로 메트릭 수집 가능 (MSK와 동등)

### 4-5. Terraform 모듈 구조

**backend.tf** — tfstate S3 저장

```hcl
terraform {
  backend "s3" {
    bucket         = "my-campaign-tfstate"
    key            = "prod/terraform.tfstate"
    region         = "ap-northeast-2"
    encrypt        = true
    dynamodb_table = "terraform-state-lock"  # 동시 수정 방지
  }
}
```

> **왜 DynamoDB lock인가?**
> 두 명이 동시에 `terraform apply`를 실행하면 tfstate가 충돌합니다.
> DynamoDB로 분산 락을 걸어 한 번에 한 명만 apply할 수 있도록 합니다.

**modules/ecs/main.tf** — ECS Fargate 핵심

```hcl
resource "aws_instance" "app" {
  count         = var.app_instance_count   # AI가 이 값을 변경
  ami           = var.app_ami_id
  instance_type = "t3.medium"
  subnet_id     = var.public_subnet_id

  iam_instance_profile = aws_iam_instance_profile.app.name

  user_data = <<-EOF
    #!/bin/bash
    # CodeDeploy Agent 설치
    yum install -y ruby wget
    wget https://aws-codedeploy-ap-northeast-2.s3.amazonaws.com/latest/install
    chmod +x ./install && ./install auto
  EOF

  tags = { Name = "campaign-app-${count.index}" }
}

resource "aws_codedeploy_deployment_group" "app" {
  app_name               = aws_codedeploy_app.app.name
  deployment_group_name  = "campaign-app-group"
  service_role_arn       = aws_iam_role.codedeploy.arn

  deployment_style {
    deployment_option = "WITH_TRAFFIC_CONTROL"
    deployment_type   = "BLUE_GREEN"   # v2에서 실제 적용
  }

  load_balancer_info {
    target_group_info { name = aws_lb_target_group.app.name }
  }
}
```

**modules/bastion/main.tf** — Bastion (MCP 서버)

```hcl
resource "aws_instance" "bastion" {
  ami           = "ami-0c9c942bd7bf113a2"  # Amazon Linux 2023
  instance_type = "t3.small"
  subnet_id     = var.public_subnet_id

  # Access Key 없이 IAM Role로 AWS API 접근
  iam_instance_profile = aws_iam_instance_profile.bastion.name

  tags = { Name = "mcp-bastion" }
}

resource "aws_iam_role_policy" "bastion" {
  role = aws_iam_role.bastion.id
  policy = jsonencode({
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "cloudwatch:GetMetricData",
          "cloudwatch:ListMetrics",
          "ec2:DescribeInstances",
          "ec2:StartInstances",
          "ec2:StopInstances",
          "codedeploy:CreateDeployment",
          "elasticache:DescribeCacheClusters",
          "rds:DescribeDBInstances"
        ]
        Resource = "*"
      }
    ]
  })
}
```

> **왜 IAM Role인가?**
> Access Key는 파일에 저장되어 유출 위험이 있습니다.
> IAM Role은 인스턴스에 임시 자격증명을 자동 주입하여
> 키 파일이 필요 없고 자동 만료됩니다.

### 4-6. Terraform 사용 순서

```bash
# 1. 기존 수동 인프라 코드화 (import)
terraform import aws_vpc.main vpc-xxxxxxxx
terraform import aws_db_instance.main mydb-instance

# 2. 신규 인프라 생성
terraform plan   # 변경 사항 미리 확인
terraform apply  # 실제 적용

# 3. tfstate 확인
aws s3 ls s3://my-campaign-tfstate/prod/
```

---

## 5. Phase 2 — Redis 고도화

### 5-1. 왜 Redis Cluster인가?

단일 Redis 인스턴스는 초당 약 10만 ops 처리가 한계입니다.
100만 트래픽에서 Rate Limiter + DECR + LPUSH가 동시에 호출되면
단일 인스턴스는 병목이 됩니다.

Redis Cluster는 데이터를 16,384개의 슬롯으로 나누어
여러 노드에 분산 저장합니다. 3 샤드 구성 시 처리량이 3배가 됩니다.

**구성: 3 샤드 × (Primary 1 + Replica 1) = 6 노드**

```
Shard 1: slots 0~5460     Primary + Replica
Shard 2: slots 5461~10922 Primary + Replica
Shard 3: slots 10923~16383 Primary + Replica
```

### 5-2. Redis Cluster에서 Lua 스크립트 주의사항

Redis Cluster에서 Lua 스크립트는 **동일한 해시 슬롯에 있는 키만** 접근할 수 있습니다.

본 설계는 Lua 스크립트가 `stock:campaign:{campaignId}` **단일 키만** 사용하므로
해시 태그 없이도 정상 동작합니다.
(Global Sequence를 별도 INCR로 발급하지 않기 때문)

### 5-3. Redis 키 설계

```
# 재고 (String, 숫자)
# DECR 반환값(잔여 재고)으로 선착순 순번 계산 가능 (totalStock - remaining = 순번)
stock:campaign:{campaignId}
예) stock:campaign:13 = "100"

# Redis Queue (List, 스파이크 방지 내부 버퍼)
queue:campaign:{campaignId}:participation
예) LPUSH queue:campaign:13:participation {campaignId:userId:historyId}

# Rate Limiter (String, TTL)
ratelimit:user:{userId}:campaign:{campaignId}
예) ratelimit:user:1:campaign:13 = "1"  TTL: 10초

# 폴링 결과 캐시 (String, TTL)
participation:result:{userId}:{campaignId}
예) participation:result:1:13 = "SUCCESS"  TTL: 300초
# Consumer가 SUCCESS 저장 시 함께 기록

# 활성 캠페인 목록 (Set, Bridge가 순회할 대상)
active:campaigns
예) SADD active:campaigns 13 27 31
```

### 5-4. Lua 스크립트 설계

**decrease-stock.lua** — 재고 차감

```lua
-- KEYS[1]: stock:campaign:{campaignId}
local stock = redis.call('GET', KEYS[1])
if stock == false then
    return -1  -- 키 없음 (캠페인 미초기화)
end
if tonumber(stock) > 0 then
    return redis.call('DECR', KEYS[1])  -- 차감 후 잔여 재고 반환
else
    return -1  -- 재고 소진
end
```

> **왜 Lua 스크립트로 감싸는가?**
> GET → 조건 분기 → DECR 세 단계를 원자적으로 실행하기 위해서입니다.
> 별도 INCR 없이 DECR 반환값(잔여 재고)으로 선착순 순번을 계산합니다.
> (totalStock - remaining = 순번, 동일 슬롯 제약 없음)

### 5-5. Redis 초기화 및 정리

```java
// 캠페인 시작 시
public void initializeCampaign(Long campaignId, Long stock) {
    String stockKey = "stock:campaign:" + campaignId;

    redisTemplate.opsForValue().set(stockKey, String.valueOf(stock));
    redisTemplate.opsForSet().add("active:campaigns", String.valueOf(campaignId));

    log.info("✅ 캠페인 Redis 초기화 - ID: {}, Stock: {}", campaignId, stock);
}

// 캠페인 종료 시
public void closeCampaign(Long campaignId) {
    redisTemplate.delete("stock:campaign:" + campaignId);
    redisTemplate.opsForSet().remove("active:campaigns", String.valueOf(campaignId));

    log.info("캠페인 Redis 정리 완료 - ID: {}", campaignId);
}
```

---

## 6. Phase 2 — Kafka 고도화

### 6-1. 왜 파티션 10개인가?

v1 실험 결과 파티션 수와 TPS는 비례하지만
10개 이상에서는 Consumer 간 리밸런싱 오버헤드가 증가합니다.
100만 트래픽에서 Bridge가 조절된 속도로 발행하므로
파티션 10개로 충분한 처리량을 확보할 수 있습니다.

### 6-2. Topic 설계

```
campaign-participation-topic
  partitions:         10
  replication-factor: 3        # 3-broker 클러스터, 고가용성
  retention:          24h      # 하루치 재처리 가능
  acks:               all      # 모든 replica 기록 확인 후 성공

campaign-participation-topic.dlq
  partitions:         3
  replication-factor: 3
  retention:          7d       # DLQ는 더 오래 보관
```

### 6-3. 메시지 구조

```json
{
  "campaignId": 13,
  "userId":     1042,
  "historyId":  101868,
  "timestamp":  1735120000000
}
```

> **왜 historyId를 포함하는가?**
> Consumer가 PENDING 레코드를 `campaign_id + user_id`로 찾으면
> unique constraint 때문에 정확하지만 인덱스 탐색이 필요합니다.
> `historyId`(PK)로 직접 조회하면 O(1)이고 더 명확합니다.
>
> **sequence 필드는 포함하지 않는가?**
> Lua DECR 반환값(잔여 재고) 자체가 선착순 기준이 됩니다.
> totalStock - remaining = 순번이므로 별도 글로벌 시퀀스 키가 불필요합니다.

### 6-4. Producer 설정 (Bridge에서만 발행)

```yaml
spring:
  kafka:
    producer:
      acks: all
      retries: 2147483647          # Integer.MAX_VALUE
      enable-idempotence: true
      compression-type: lz4        # 네트워크 비용 절감
      properties:
        max.in.flight.requests.per.connection: 5
        delivery.timeout.ms: 120000
        buffer.memory: 134217728   # 128MB
        batch-size: 65536          # 64KB
        linger.ms: 10              # 10ms 대기 후 배치 발행
```

> **왜 linger.ms를 10ms로 낮추는가?**
> Bridge는 배치 단위로 발행하므로 이미 배치화가 되어 있습니다.
> linger.ms를 너무 높이면 Bridge의 처리 주기와 겹쳐 지연이 발생합니다.

### 6-5. Consumer 설정

```yaml
spring:
  kafka:
    consumer:
      max-poll-records: 100        # 배치 사이즈
      max-poll-interval-ms: 300000 # 5분
      session-timeout-ms: 45000
      auto-offset-reset: earliest
      enable-auto-commit: false    # 수동 커밋
```

---

## 7. Phase 2 — Spring Boot 코드 고도화

### 7-1. 패키지 구조 (변경사항 중심)

```
io.eventdriven.campaign
├── api/
│   ├── controller/
│   │   ├── ParticipationController.java  ← 전면 재설계
│   │   └── ...
│   └── filter/
│       └── RateLimitFilter.java          ← 신규
│
├── application/
│   ├── bridge/
│   │   └── ParticipationBridge.java      ← 신규 (핵심)
│   ├── consumer/
│   │   └── ParticipationEventConsumer.java ← 수정
│   ├── scheduler/
│   │   ├── PendingRetryScheduler.java    ← 신규
│   │   └── RedisSyncScheduler.java       ← 신규
│   └── service/
│       ├── ParticipationService.java     ← 전면 재설계
│       ├── RedisStockService.java        ← 수정 (키 변경, 신규 메서드)
│       └── ...
└── ...
```

### 7-2. ParticipationController — 재설계

**전체 흐름:**

```
POST /api/campaigns/{campaignId}/participation
  ↓
1. Rate Limiter 확인 (서비스 내에서)
2. Redis Lua 재고 차감 (원자적, DECR 반환값 = 잔여 재고)
3. DB PENDING INSERT (historyId 획득)
4. Redis LPUSH Queue
5. 202 반환
```

```java
@PostMapping("/{campaignId}/participation")
@Transactional
public ResponseEntity<ApiResponse<Void>> participate(
        @PathVariable Long campaignId,
        @RequestBody @Valid ParticipationRequest request) {

    Long userId = request.getUserId();

    // 1. Rate Limiter
    if (!rateLimitService.tryAcquire(campaignId, userId)) {
        return ResponseEntity.status(429)
                .body(ApiResponse.fail("잠시 후 다시 시도해주세요."));
    }

    // 2. Redis 재고 차감 (원자적, 잔여 재고 반환)
    long remaining = redisStockService.decrease(campaignId);

    if (remaining < 0) {
        return ResponseEntity.status(400)
                .body(ApiResponse.fail("선착순이 마감되었습니다."));
    }

    // 3. DB PENDING 저장 (자리 선점)
    ParticipationHistory history = participationService.savePending(
            campaignId, userId);

    // 4. Redis Queue 적재
    redisQueueService.enqueue(campaignId, userId, history.getId());

    // 5. 202 반환
    return ResponseEntity.accepted()
            .body(ApiResponse.success("접수됐습니다. 잠시 후 결과를 확인해주세요."));
}
```

> **왜 @Transactional을 여기서 사용하는가?**
> DB PENDING 저장이 실패하면 Redis DECR만 됩니다.
> @Transactional로 DB 저장만 원자화하고,
> Redis 롤백은 best-effort(INCR 시도)로 처리합니다.
> 동기화 배치가 최종 정합성을 보장합니다.

### 7-3. RateLimitService

```java
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final RedisTemplate<String, String> redisTemplate;
    private static final Duration TTL = Duration.ofSeconds(10);

    public boolean tryAcquire(Long campaignId, Long userId) {
        String key = "rl:" + userId + ":" + campaignId;

        // SET key 1 NX EX 10 → null이면 이미 존재 (중복 요청)
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, "1", TTL);

        return Boolean.TRUE.equals(acquired);
    }
}
```

> **왜 Filter가 아닌 Service로 구현하는가?**
> Rate Limiter가 campaignId와 userId 조합으로 동작하므로
> HTTP 경로 파싱이 필요합니다. Filter보다 Controller에서
> 비즈니스 로직으로 처리하는 것이 의도가 명확합니다.

### 7-4. RedisStockService — 신규 메서드

```java
/**
 * 재고 차감 (원자적)
 * @return 차감 후 잔여 재고 (음수이면 재고 소진)
 *         totalStock - remaining = 선착순 순번
 */
public long decrease(Long campaignId) {
    String stockKey = "stock:campaign:" + campaignId;

    Long result = redisTemplate.execute(
            decreaseStockScript,
            List.of(stockKey)
    );

    return result != null ? result : -1L;
}
```

### 7-5. RedisQueueService

```java
@Service
@RequiredArgsConstructor
public class RedisQueueService {

    private final RedisTemplate<String, String> redisTemplate;
    private static final long MAX_QUEUE_SIZE = 100_000L;

    public void enqueue(Long campaignId, Long userId, Long historyId) {
        String queueKey = "{c:" + campaignId + "}:queue";

        // 큐 적체 한도 초과 시 503 (정상 운영 중에는 발생하지 않아야 함)
        Long size = redisTemplate.opsForList().size(queueKey);
        if (size != null && size >= MAX_QUEUE_SIZE) {
            throw new QueueFullException(campaignId);
        }

        String message = campaignId + ":" + userId + ":" + historyId;
        redisTemplate.opsForList().leftPush(queueKey, message);
    }

    /**
     * Bridge에서 호출 — 최대 batchSize개 꺼내기
     */
    public List<String> dequeue(Long campaignId, int batchSize) {
        String queueKey = "{c:" + campaignId + "}:queue";

        // Redis 7.0+: LMPOP count queueKey RIGHT COUNT batchSize
        // Redis 6.x : pipeline으로 RPOP batchSize번 호출
        return redisTemplate.executePipelined((RedisCallback<Object>) conn -> {
            for (int i = 0; i < batchSize; i++) {
                conn.listCommands().rPop(queueKey.getBytes());
            }
            return null;
        }).stream()
          .filter(Objects::nonNull)
          .map(Object::toString)
          .collect(Collectors.toList());
    }
}
```

> **왜 BRPOP이 아닌 LMPOP / pipeline RPOP인가?**
> BRPOP은 한 번에 1개만 꺼낼 수 있습니다 (count 파라미터 없음).
> 배치 처리를 위해서는 LMPOP(Redis 7.0+) 또는
> pipeline으로 RPOP을 N번 호출하는 방식을 사용합니다.

### 7-6. Bridge 컴포넌트 — 핵심 신규

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class ParticipationBridge {

    private final RedisTemplate<String, String> redisTemplate;
    private final RedisQueueService redisQueueService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JsonMapper jsonMapper;

    private static final String TOPIC      = "campaign-participation-topic";
    private static final int    BATCH_SIZE = 100;
    private static final int    MAX_RETRY  = 3;

    /**
     * 100ms마다 실행 — 모든 활성 캠페인 큐 처리
     */
    @Scheduled(fixedDelay = 100)
    public void bridge() {
        Set<String> activeCampaigns = redisTemplate.opsForSet()
                .members("active:campaigns");

        if (activeCampaigns == null || activeCampaigns.isEmpty()) return;

        for (String campaignIdStr : activeCampaigns) {
            Long campaignId = Long.parseLong(campaignIdStr);
            processQueue(campaignId);
        }
    }

    private void processQueue(Long campaignId) {
        List<String> messages = redisQueueService.dequeue(campaignId, BATCH_SIZE);
        if (messages.isEmpty()) return;

        List<String> failed = new ArrayList<>();

        for (String msg : messages) {
            try {
                kafkaTemplate.send(TOPIC, msg).get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.error("🚨 Kafka 발행 실패 - 재적재: {}", msg, e);
                failed.add(msg);
            }
        }

        // 실패한 메시지 Queue 재적재
        if (!failed.isEmpty()) {
            for (String msg : failed) {
                redisQueueService.requeueWithRetry(campaignId, msg, MAX_RETRY);
            }
        }

        log.info("Bridge 처리 완료 - Campaign: {}, 성공: {}, 실패: {}",
                campaignId, messages.size() - failed.size(), failed.size());
    }
}
```

> **왜 Bridge를 별도 컴포넌트로 분리하는가?**
>
> API가 직접 Kafka에 발행하면:
> - 100만 요청 = 100만 개의 동시 Kafka 발행 시도
> - Producer 버퍼(128MB) 초과 → OOM 또는 처리량 급락
>
> Bridge는 Redis Queue라는 댐을 두어
> Kafka로 흘러가는 유량을 배치 단위(100개/100ms = 1,000건/초)로 제어합니다.
> API는 Redis LPUSH(O(1), 마이크로초)만 하고 즉시 202를 반환하므로
> 응답 지연이 최소화됩니다.

### 7-7. Kafka Consumer — 수정

```java
@KafkaListener(
        topics = "campaign-participation-topic",
        groupId = "campaign-participation-group",
        containerFactory = "kafkaListenerContainerFactory"
)
@Transactional
public void consume(List<ConsumerRecord<String, String>> records,
                    Acknowledgment ack) {
    for (ConsumerRecord<String, String> record : records) {
        processRecord(record);
    }
    ack.acknowledge();
}

private void processRecord(ConsumerRecord<String, String> record) {
    // 1. 중복 체크 (partition + offset 조합)
    if (historyRepo.existsByKafkaPartitionAndKafkaOffset(
            record.partition(), record.offset())) {
        return; // 멱등성 — 이미 처리됨
    }

    BridgeMessage msg = parse(record.value());

    // 2. PENDING 레코드 조회 (historyId로 직접 조회 — O(1))
    ParticipationHistory history = historyRepo.findById(msg.getHistoryId())
            .orElse(null);

    if (history == null || history.getStatus() != ParticipationStatus.PENDING) {
        log.warn("⚠️ PENDING 레코드 없음 또는 이미 처리됨 - historyId: {}",
                msg.getHistoryId());
        return;
    }

    // 3. PENDING → SUCCESS 확정
    history.confirm(
            ParticipationStatus.SUCCESS,
            record.partition(),
            record.offset(),
            record.timestamp()
    );

    // 4. 폴링 결과 Redis 캐시 (DB 부하 방지)
    redisTemplate.opsForValue().set(
            "result:" + msg.getCampaignId() + ":" + msg.getUserId(),
            "SUCCESS",
            Duration.ofMinutes(5)
    );

    log.info("✅ 확정 완료 - Campaign: {}, User: {}, Seq: {}",
            msg.getCampaignId(), msg.getUserId(), history.getSequence());
}
```

### 7-8. 결과 폴링 API — 신규

```java
/**
 * 사용자가 202 받은 후 결과 확인
 * GET /api/campaigns/{campaignId}/participation/{userId}/result
 */
@GetMapping("/{campaignId}/participation/{userId}/result")
public ResponseEntity<ApiResponse<?>> getResult(
        @PathVariable Long campaignId,
        @PathVariable Long userId) {

    // 1. Redis 캐시 우선 조회 (Consumer가 기록)
    String cached = redisTemplate.opsForValue()
            .get("result:" + campaignId + ":" + userId);

    if (cached != null) {
        return ResponseEntity.ok(ApiResponse.success(Map.of("status", cached)));
    }

    // 2. Redis 없으면 DB fallback
    return historyRepo
            .findByCampaignIdAndUserId(campaignId, userId)
            .map(h -> ResponseEntity.ok(
                    ApiResponse.success(Map.of("status", h.getStatus().name()))))
            .orElseGet(() -> ResponseEntity.ok(
                    ApiResponse.success(Map.of("status", "PENDING"))));
}
```

> **왜 Redis 캐시를 먼저 조회하는가?**
> 100만 명이 동시에 폴링하면 초당 수십만 건의 DB SELECT가 발생합니다.
> Consumer가 SUCCESS 저장 시 Redis에도 함께 기록하면
> 대부분의 폴링이 Redis에서 처리되어 DB 부하를 95% 이상 줄일 수 있습니다.

### 7-9. PENDING TTL 재처리 스케줄러 — 신규

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingRetryScheduler {

    private final ParticipationHistoryRepository historyRepo;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final RedisStockService redisStockService;

    private static final int PENDING_TTL_MINUTES = 5;
    private static final int MAX_RETRY_COUNT     = 3;

    /**
     * 5분마다 실행 — PENDING 장기 방치 건 처리
     * 인덱스 필수: INDEX idx_status_created (status, created_at)
     */
    @Scheduled(fixedDelay = 300_000)
    public void retryPending() {
        LocalDateTime cutoff = LocalDateTime.now()
                .minusMinutes(PENDING_TTL_MINUTES);

        List<ParticipationHistory> staleList = historyRepo
                .findByStatusAndCreatedAtBefore(
                        ParticipationStatus.PENDING, cutoff);

        for (ParticipationHistory history : staleList) {
            if (history.getRetryCount() < MAX_RETRY_COUNT) {
                // 재처리: Kafka 재발행
                kafkaTemplate.send("campaign-participation-topic",
                        buildMessage(history));
                history.incrementRetry();
                log.info("⚠️ PENDING 재처리 시도 - historyId: {}, retryCount: {}",
                        history.getId(), history.getRetryCount());
            } else {
                // 최대 재시도 초과 → FAIL 처리 + 재고 복구
                history.fail();
                redisStockService.incrementStock(history.getCampaignId());
                log.error("❌ PENDING 최대 재시도 초과 → FAIL - historyId: {}",
                        history.getId());
            }
        }
    }
}
```

> **왜 (status, created_at) 복합 인덱스가 필수인가?**
> `WHERE status = 'PENDING' AND created_at < ?` 쿼리에서
> 인덱스 없이 100만 건 테이블을 풀스캔하면 수 초가 걸립니다.
> 해당 복합 인덱스로 PENDING 건만 빠르게 필터링합니다.

### 7-10. Redis-DB 동기화 배치 — 신규

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisSyncScheduler {

    private final CampaignRepository campaignRepo;
    private final ParticipationHistoryRepository historyRepo;
    private final RedisStockService redisStockService;

    /**
     * 10분마다 실행 — Redis 재고와 DB 실제 사용 수 비교 후 보정
     */
    @Scheduled(fixedDelay = 600_000)
    public void syncStock() {
        List<Campaign> activeCampaigns = campaignRepo.findByStatus(CampaignStatus.ACTIVE);

        for (Campaign campaign : activeCampaigns) {
            Long usedCount = historyRepo.countByCampaignIdAndStatusIn(
                    campaign.getId(),
                    List.of(ParticipationStatus.PENDING, ParticipationStatus.SUCCESS));

            long correctStock = campaign.getTotalStock() - usedCount;
            Long redisStock   = redisStockService.getStock(campaign.getId());

            if (redisStock == null || Math.abs(redisStock - correctStock) > 0) {
                log.warn("⚠️ Redis-DB 재고 불일치 감지 - Campaign: {}, Redis: {}, DB계산: {}",
                        campaign.getId(), redisStock, correctStock);
                redisStockService.forceSet(campaign.getId(), correctStock);
                log.info("✅ Redis 재고 보정 완료 - Campaign: {}, 보정값: {}",
                        campaign.getId(), correctStock);
            }
        }
    }
}
```

---

## 8. Phase 2 — DB 스키마

### 8-1. participation_history 변경사항

```sql
-- V002__add_pending_and_constraints.sql (Flyway)

-- 1. status에 PENDING 추가
ALTER TABLE participation_history
    MODIFY COLUMN status ENUM('PENDING', 'SUCCESS', 'FAIL') NOT NULL DEFAULT 'PENDING';

-- 2. 순번/재처리 컬럼 추가
ALTER TABLE participation_history
    ADD COLUMN sequence    BIGINT COMMENT 'Redis DECR 기반 선착순 번호 (totalStock - remaining)',
    ADD COLUMN retry_count INT    NOT NULL DEFAULT 0 COMMENT 'PENDING 재처리 횟수';

-- 3. 중복 발급 방지 unique constraint
ALTER TABLE participation_history
    ADD UNIQUE KEY uk_campaign_user (campaign_id, user_id);

-- 4. PENDING 재처리 스케줄러용 인덱스
ALTER TABLE participation_history
    ADD INDEX idx_status_created (status, created_at);
```

### 8-2. 최종 스키마

```sql
CREATE TABLE participation_history (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    campaign_id     BIGINT NOT NULL,
    user_id         BIGINT NOT NULL,
    status          ENUM('PENDING', 'SUCCESS', 'FAIL') NOT NULL DEFAULT 'PENDING',
    sequence        BIGINT          COMMENT '선착순 번호 (Redis DECR 반환값 기반)',
    retry_count     INT NOT NULL DEFAULT 0,
    kafka_partition INT,
    kafka_offset    BIGINT,
    kafka_timestamp BIGINT,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_campaign_user (campaign_id, user_id),
    INDEX      idx_status_created (status, created_at),
    INDEX      idx_campaign_user  (campaign_id, user_id),
    INDEX      idx_partition_offset (kafka_partition, kafka_offset)
);
```

---

## 9. Phase 3 — MCP 서버 (Python/FastAPI)

### 9-1. 왜 Python + FastAPI인가?

Anthropic의 MCP SDK 레퍼런스 구현이 Python입니다.
boto3(AWS SDK)와의 통합이 간편하고,
FastAPI의 SSE(Server-Sent Events) 지원이 내장되어 있어
MCP 표준 통신 방식을 쉽게 구현할 수 있습니다.

### 9-2. 디렉토리 구조

```
mcp-server/
├── main.py              # FastAPI 앱 진입점
├── tools/
│   ├── metrics.py       # CloudWatch 메트릭 조회
│   ├── scaling.py       # ECS 스케일링
│   ├── kafka_tools.py   # Kafka Consumer Lag 조회
│   ├── redis_tools.py   # Redis 큐 상태 조회
│   └── batch_tools.py   # 동기화 배치 트리거
├── auth.py              # IAM Role 기반 인증
├── requirements.txt
└── Dockerfile
```

### 9-3. main.py — MCP 서버 핵심

```python
from fastapi import FastAPI
from mcp.server.fastmcp import FastMCP
from tools.metrics import get_metrics
from tools.scaling import scale_service
from tools.kafka_tools import get_kafka_lag
from tools.redis_tools import get_redis_queue_depth

app = FastAPI()
mcp = FastMCP("campaign-infra-controller")


@mcp.tool()
async def get_metrics(service: str, minutes: int = 5) -> dict:
    """
    CloudWatch에서 서비스 메트릭을 조회합니다.
    service: 'app' | 'kafka' | 'redis' | 'rds'
    """
    return await fetch_cloudwatch_metrics(service, minutes)


@mcp.tool()
async def scale_service(instance_ids: list[str], action: str) -> dict:
    """
    EC2 앱 서버를 시작/중지합니다.
    action: 'start' | 'stop'
    """
    if action not in ("start", "stop"):
        return {"error": "action은 start 또는 stop만 허용됩니다."}
    return await update_ec2_instances(instance_ids, action)


@mcp.tool()
async def get_kafka_lag(consumer_group: str) -> dict:
    """
    Kafka Consumer Lag을 조회합니다.
    """
    return await fetch_kafka_lag(consumer_group)


@mcp.tool()
async def get_queue_depth(campaign_id: int) -> dict:
    """
    Redis Queue의 현재 적체량을 조회합니다.
    """
    return await fetch_redis_queue_depth(campaign_id)


@mcp.tool()
async def trigger_sync_batch(campaign_id: int) -> dict:
    """
    Redis-DB 재고 동기화 배치를 즉시 트리거합니다.
    """
    return await call_spring_batch_api(campaign_id)


# SSE 엔드포인트 (MCP 표준 통신)
app.mount("/", mcp.get_asgi_app())
```

### 9-4. tools/scaling.py

```python
import boto3

ecs_client = boto3.client('ecs', region_name='ap-northeast-2')
# IAM Role → 자동 자격증명 주입, Access Key 불필요


async def update_ec2_instances(instance_ids: list, action: str) -> dict:
    ec2 = boto3.client('ec2', region_name='ap-northeast-2')
    if action == 'start':
        response = ec2.start_instances(InstanceIds=instance_ids)
        states = response['StartingInstances']
    else:
        response = ec2.stop_instances(InstanceIds=instance_ids)
        states = response['StoppingInstances']
    return {
        "action":   action,
        "instances": [{"id": s['InstanceId'], "state": s['CurrentState']['Name']} for s in states]
    }
```

---

## 10. Phase 3 — AI 오케스트레이션 파이프라인

### 10-1. 파이프라인 흐름

```
[1] 메트릭 수집 (5분마다 CloudWatch 조회)
      TPS, 에러율, Redis 큐 깊이, Kafka Consumer Lag, CPU/메모리
        ↓
[2] Claude API 분석
      "현재 메트릭을 분석하고 조치가 필요한지 판단하세요"
        ↓
[3] 판단 분류
      NORMAL   → 로그만 기록
      WARNING  → Slack 알림 + 팀장 승인 후 실행
      CRITICAL → Slack 알림 + 팀장 승인 후 실행
        ↓
[4] WARNING/CRITICAL: Slack 메시지 발송 + 승인 대기 (최대 10분)
    승인 클릭 → Claude가 실행
    거부/타임아웃 → 실행 없이 로그만
        ↓
[5] 실행 결과 DynamoDB 감사 로그 기록

> **왜 WARNING도 승인이 필요한가?**
> AI의 잘못된 판단으로 불필요한 인프라 실행이 발생할 수 있습니다.
> "AI는 판단, 실행은 항상 사람" 원칙을 지켜
> WARNING/CRITICAL 구분 없이 모든 실행은 승인 후에만 동작합니다.
```

### 10-2. orchestrator.py

```python
import anthropic
import asyncio
from datetime import datetime
from tools.metrics import get_metrics
from tools.scaling import scale_service
from audit_log import save_audit_log
from slack_approval import request_approval

client = anthropic.Anthropic()

SYSTEM_PROMPT = """
당신은 캠페인 서비스의 인프라 운영 AI입니다.
메트릭을 분석하고 다음 기준으로 판단하세요:

NORMAL  : 모든 지표 정상
WARNING : TPS < 기준치 50% 또는 에러율 > 1% 또는 Kafka Lag > 10만
CRITICAL: 비용 $50/h 초과 예상 또는 Redis 메모리 > 80% 또는 DB 연결 고갈

판단 결과를 JSON으로 반환하세요:
{
  "level": "NORMAL|WARNING|CRITICAL",
  "reason": "판단 근거",
  "action": "수행할 조치 (없으면 null)",
  "tool": "호출할 MCP Tool (없으면 null)",
  "params": {}
}
"""

async def orchestrate():
    # 중복 실행 방지 — 승인 대기 중이면 스킵
    if await redis.exists("approval:pending"):
        log.info("승인 대기 중 — orchestrate 스킵")
        return

    # 1. 메트릭 수집
    metrics = await get_metrics("all", minutes=5)

    # 2. Claude 분석 (JSON 파싱 실패 시 NORMAL 폴백)
    try:
        response = client.messages.create(
            model="claude-opus-4-6",
            max_tokens=1024,
            system=SYSTEM_PROMPT,
            messages=[{
                "role": "user",
                "content": f"현재 메트릭:\n{metrics}\n\n조치가 필요한지 분석하세요."
            }]
        )
        decision = parse_json(response.content[0].text)
    except Exception as e:
        log.error(f"Claude 응답 파싱 실패 → NORMAL 처리: {e}")
        decision = {"level": "NORMAL", "reason": "파싱 실패", "action": None, "params": {}}

    # 3. 판단에 따른 실행 (WARNING/CRITICAL 모두 Slack 승인 필요)
    result = None
    if decision["level"] == "NORMAL":
        pass

    elif decision["level"] in ("WARNING", "CRITICAL"):
        # 승인 대기 플래그 설정 (10분 TTL — Slack 승인 타임아웃과 동일)
        await redis.setex("approval:pending", 600, "1")
        try:
            approved = await request_approval(
                level=decision["level"],
                reason=decision["reason"],
                action=decision["action"],
                params=decision["params"]
            )
            if approved:
                result = await scale_service(
                    decision["params"]["instance_ids"],
                    decision["params"]["action"]
                )
        finally:
            await redis.delete("approval:pending")  # 항상 플래그 해제

    # 4. 감사 로그 기록
    await save_audit_log({
        "timestamp": datetime.utcnow().isoformat(),
        "metrics":   metrics,
        "decision":  decision,
        "result":    result,
        "approved":  result is not None
    })
```

---

## 11. Phase 3 — Slack 승인 + DynamoDB 감사 로그

### 11-1. Slack 승인 워크플로우

```python
# slack_approval.py

import asyncio
from slack_sdk import WebClient
from slack_sdk.models.blocks import *

slack = WebClient(token=os.environ["SLACK_BOT_TOKEN"])

async def request_approval(reason: str, action: str, params: dict) -> bool:
    """
    Slack으로 승인 요청 후 버튼 클릭 결과 대기 (최대 10분)
    """
    approval_id = str(uuid.uuid4())

    # Slack 메시지 발송
    slack.chat_postMessage(
        channel="#campaign-ops",
        blocks=[
            {
                "type": "section",
                "text": {
                    "type": "mrkdwn",
                    "text": f"🚨 *고위험 스케일링 승인 요청*\n\n"
                            f"*판단 근거:* {reason}\n"
                            f"*조치 내용:* {action}\n"
                            f"*파라미터:* {params}"
                }
            },
            {
                "type": "actions",
                "elements": [
                    {
                        "type": "button",
                        "text": {"type": "plain_text", "text": "✅ 승인"},
                        "style": "primary",
                        "value": f"approve:{approval_id}"
                    },
                    {
                        "type": "button",
                        "text": {"type": "plain_text", "text": "❌ 거부"},
                        "style": "danger",
                        "value": f"reject:{approval_id}"
                    }
                ]
            }
        ]
    )

    # 버튼 클릭 대기 (10분 타임아웃)
    return await wait_for_approval(approval_id, timeout=600)
```

### 11-2. DynamoDB 감사 로그

> **왜 DynamoDB인가?**
> AI의 모든 판단과 실행 이력은 빠른 쓰기와 시간 기반 조회가 필요합니다.
> RDS에 저장하면 운영 DB에 부담을 주고,
> DynamoDB는 Write-heavy 워크로드에 최적화되어 있으며
> TTL 설정으로 오래된 로그를 자동 삭제할 수 있습니다.

```python
# audit_log.py

import boto3
from decimal import Decimal

dynamodb = boto3.resource('dynamodb', region_name='ap-northeast-2')
table = dynamodb.Table('ai-audit-log')

async def save_audit_log(log: dict):
    table.put_item(Item={
        "log_id":    str(uuid.uuid4()),           # PK
        "timestamp": log["timestamp"],            # SK (시간 기준 조회)
        "level":     log["decision"]["level"],
        "reason":    log["decision"]["reason"],
        "action":    log["decision"].get("action"),
        "result":    str(log.get("result")),
        "approved":  log.get("approved", False),
        "ttl":       int(time.time()) + 90 * 86400  # 90일 후 자동 삭제
    })
```

**DynamoDB 테이블 설계:**

```
Table: ai-audit-log
  PK: log_id (String)
  SK: timestamp (String, ISO-8601)
  TTL: ttl (Number, epoch seconds)

GSI: level-timestamp-index
  PK: level (NORMAL|WARNING|CRITICAL)
  SK: timestamp
  → 레벨별 이력 조회용
```

---

## 12. 장애 대응 매트릭스

| 장애 상황 | 영향 범위 | 즉시 대응 | 복구 후 처리 |
|---|---|---|---|
| Redis 단일 노드 다운 | 해당 슬롯 요청 실패 | Cluster 자동 Replica 승격 | 동기화 배치 즉시 실행 |
| Redis Cluster 전체 다운 | 서비스 중단 | 503 반환 | 복구 후 DB 기준 재고 복원 |
| Kafka 브로커 1대 다운 | 없음 (replication=3) | 나머지 2대에서 자동 계속 | 브로커 복구 후 리밸런싱 |
| Kafka 전체 다운 | Bridge 발행 실패 | Redis Queue 적재 유지 | 복구 후 Bridge 이어서 발행 |
| Bridge 다운 | Queue 적체 | Queue 누적 (유실 없음) | 재시작 후 이어서 처리 |
| Consumer 다운 | SUCCESS 확정 지연 | PENDING 상태 유지 | 재시작 후 offset 기준 재처리 |
| 앱 EC2 다운 | 처리 중 요청 손실 | ALB가 정상 인스턴스로 라우팅 | CodeDeploy 재배포, PENDING TTL 스케줄러 복구 |
| DB 다운 | 서비스 중단 | 503 반환 | RDS Multi-AZ 자동 페일오버 |
| MCP 서버 다운 | AI 자동화 중단 | 수동 운영 전환 | 재시작 후 자동 재개 |

---

## 13. 미확정 파라미터 기준값

| 파라미터 | 기준값 | 근거 |
|---|---|---|
| Rate Limit TTL | 10초 | 동일 사용자 중복 요청 방지 최소 간격 |
| Redis Queue 최대 크기 | 100,000 | 브릿지 처리량(1,000건/초) × 100초 여유 |
| Bridge 배치 사이즈 | 100건 | Kafka 배치 발행 효율 vs 지연 균형 |
| Bridge 실행 주기 | 100ms | 1,000건/초 처리량 목표 |
| Bridge 최대 재시도 | 3회 | 일시적 Kafka 장애 허용 |
| PENDING TTL | 5분 | Consumer 처리 지연 허용 최대 시간 |
| PENDING 재처리 횟수 | 3회 | 재처리 실패 시 FAIL 처리 기준 |
| 동기화 배치 주기 | 10분 | Redis-DB 불일치 허용 최대 시간 |
| 폴링 Redis 캐시 TTL | 5분 | SUCCESS 이후 재조회 불필요 시점 |
| 폴링 클라이언트 타임아웃 | 3분 | PENDING TTL(5분)보다 짧게 설정 |
| Kafka 파티션 수 | 10 | v1 실험 결과 최적값 |
| Consumer Lag 경고 기준 | 100,000건 | 브릿지 처리량 대비 허용 적체량 |
| AI 오케스트레이터 주기 | 5분 | CloudWatch 메트릭 최소 수집 단위 |
| Slack 승인 타임아웃 | 10분 | 미승인 시 자동 취소 |
| DynamoDB 로그 TTL | 90일 | 운영 감사 보관 기간 |

---

*이 문서는 구현 진행에 따라 업데이트됩니다.*
*최종 작성: 2026-03-23*
