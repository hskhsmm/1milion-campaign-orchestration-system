# Event-Driven First-Come-First-Served Campaign System

> **10만 건 동시 트래픽 환경에서 공정한 선착순 처리를 보장하는 이벤트 시스템**
>
> *This project focuses on architectural decision-making through measurable experiments rather than feature completeness.*

[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.1-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Kafka](https://img.shields.io/badge/Apache%20Kafka-3.x-black.svg)](https://kafka.apache.org/)
[![MySQL](https://img.shields.io/badge/MySQL-8.0-blue.svg)](https://www.mysql.com/)

---

## 📑 Summary

- [프로젝트 의도](#프로젝트-의도)
- [Tech Stack](#tech-stack)
- [Kafka 파티션별 비교--분석](#kafka-파티션별-비교--분석)
- [문제 발견-db-병목과-redis-도입](#문제-발견-db-병목과-redis-도입)
- [실험-결과-개요](#실험-결과-개요)
- [db-commit-기준-실험-결과-최종-판단-기준](#db-commit-기준-실험-결과-최종-판단-기준)
- [kafka-메시지-소비-기준-실험-kafka-tps](#kafka-메시지-소비-기준-실험-kafka-tps)
- [web-실시간-모니터링-기준-분석](#web-실시간-모니터링-기준-분석)
- [실험-결론](#실험-결론)
- [배운-점-및-향후-개선-방향](#배운-점-및-향후-개선-방향)
- [왜-파티션-1개인가-순서-보장-관점](#왜-파티션-1개인가-순서-보장-관점)
- [db-원자성-보장](#db-원자성-보장)
- [dead-letter-queue-dlq](#dead-letter-queue-dlq)
- [배치-처리](#배치-처리)
- [클라우드-아키텍처](#클라우드-아키텍처)
- [아키텍처-상세-설명](#아키텍처-상세-설명)
- [author](#author)

<br><br>

## 프로젝트 의도

이 프로젝트는 수만 명이 동시에 참여하는 선착순 이벤트 환경에서  
단순한 처리 성공이 아닌 **공정한 순서 보장과 데이터 정합성**을 목표로 설계되었습니다.

특히 Kafka 파티션 수에 따른  
**처리량 증가와 순서 보장 붕괴의 트레이드오프**를  
실험과 수치로 검증하고, 이를 기반으로 아키텍처 결정을 내리는 것을 핵심 목표로 삼았습니다.

### 왜 이 프로젝트인가

기존 선착순 시스템 구현 사례들은 대부분 다음 중 하나에 머무르는 경우가 많습니다.

- 단일 서버 동기 처리
- Kafka 도입 여부 자체에만 초점을 둔 구현

그러나 실제 운영 환경에서는 다음과 같은 질문에 대한 답이 명확하지 않은 경우가 많습니다.

- Kafka는 어디까지 책임지는가?
- 파티션을 늘리면 무엇이 좋아지고, 무엇이 달라지는가?
- 순서가 일부 섞여도 최종 결과는 어떻게 보장되는가?

본 프로젝트는 기능 구현보다  
**이 질문들에 실험과 수치로 답하는 것**을 목표로 설계되었습니다.

### 핵심 질문

- 파티션을 늘리면 무조건 좋은가?
- 처리량과 공정성, 둘 중 무엇이 더 중요한가?
- 순서가 섞여도 최종 결과는 어떻게 보장하는가?

---

## Tech Stack

| 분류 | 기술 | 선택 이유 |
|------|------|-----------|
| **Language** | Java 25 | Virtual Thread 기반 고효율 I/O 처리 |
| **Framework** | Spring Boot 4.0.1 | 최신 Spring 생태계 |
| **Message Queue** | Apache Kafka | 트래픽 서지 흡수 + 파티션 기반 순서 보장 |
| **Database** | MySQL 8.0 | 트랜잭션 보장 + 참여 이력 저장 |
| **Cache** | Redis (ElastiCache) | 인메모리 재고 차감 (DB 병목 해결) |
| **Batch** | Spring Batch | 실시간 경로와 집계 로직 분리 |
| **Load Test** | k6 | 파티션별 성능 비교 실험 |
| **Cloud** | AWS (EC2, RDS, ALB, CodeDeploy) | 실제 운영 환경 구성 |

---

## Kafka 파티션별 비교 & 분석

> 이 프로젝트의 핵심 차별점

### 실험 환경 및 조건

| 구분 | 설정 |
|------|------|
| **인프라** | AWS EC2 t3.large (2 vCPU, 8GB RAM) |
| **JVM** | 서버 스펙에 맞게 조정 (-Xms2g -Xmx5g, G1GC) |
| **컨테이너** | Docker (mem_limit: 6GB) |

#### 파티션별 최적화 설정

각 파티션 환경에서 안정적인 테스트를 위해 DB 커넥션 풀과 Kafka 설정을 파티션 수에 맞게 조정했습니다.

| 설정 | 파티션 1개 | 파티션 3개 | 파티션 5개 |
|------|-----------|-----------|-----------|
| **DB Pool (max)** | 20 | 30 | 40 |
| **Producer buffer** | 128MB | 192MB | 256MB |
| **Consumer max-poll-records** | 500 | 250 | 200 |

---

### 초기 가설

Kafka 파티션 수 증가에 따라 다음과 같은 트레이드오프를 예상했습니다.

| 파티션 수 | 예상 처리량 | 예상 순서 불일치 |
|-----------|-------------|------------------|
| 1개 | 낮음 | 0% |
| 3개 | 중간 | 10%+ |
| 5개 | 높음 | 20%+ |

---

## 문제 발견: DB 병목과 Redis 도입

### 초기 실험에서 발견한 문제

초기 실험에서 파티션을 1 → 3 → 5로 증가시켜도 **TPS가 ~285에서 정체**되는 현상을 발견했습니다.

| 파티션 수 | 예상 | 실제 TPS |
|-----------|------|----------|
| 1개 | 기준 | 285 |
| 3개 | 3배 증가 | 279 |
| 5개 | 5배 증가 | 288 |

**원인**: MySQL의 원자적 UPDATE 쿼리(`UPDATE ... SET stock = stock - 1 WHERE stock > 0`)가 Row Lock을 발생시켜, 파티션을 늘려도 DB에서 직렬화되는 병목 현상.

### CPU 분석으로 확인한 병목 현상

파티션 수 증가에 따른 **CPU 사용률 패턴**을 분석한 결과, DB 병목 현상을 시각적으로 확인할 수 있었습니다.

| 파티션 수 | CPU 상승 패턴 | 부하 지속 시간 |
|-----------|---------------|----------------|
| 1개 | 서서히 상승 | 100초 |
| 3개 | 빠르게 상승 | 120초 |
| 5개 | 더 빠르게 상승 | 128초 |

**핵심 발견**: 파티션을 늘려도 처리 시간이 단축되지 않고 **오히려 증가** (100초 → 128초)
→ 이는 DB Row Lock 경합으로 인한 병목의 명확한 증거입니다.

---

#### 파티션 1개

**부하 시작점**

<img width="900" height="103" alt="p1 부하 시작점" src="https://github.com/user-attachments/assets/1bcf617f-ea1b-43f4-ae47-88c122f27f85" />
<br>

**부하 꺾이는 시점**

<img width="900" height="310" alt="image" src="https://github.com/user-attachments/assets/0d8facc0-c582-4794-8ac3-2261c78a09e2" />

<br>

**최고 CPU**

<img width="900" height="98" alt="모니터링 최고 cpu" src="https://github.com/user-attachments/assets/27de7fcc-3ea4-4c2a-839e-d270cbd08b4b" />
<br>

**부하 지속 시간**: 100초

---

#### 파티션 3개

**부하 시작점**

<img width="900" height="101" alt="p3 부하 시작점" src="https://github.com/user-attachments/assets/a55cdbd3-38e5-4f26-8209-660a857cda35" />
<br>

**부하 꺾이는 시점**

<img width="900" height="326" alt="image" src="https://github.com/user-attachments/assets/a2c895db-5f58-4d84-ae65-d670b42057f2" />

<br>

**최고 CPU**

<img width="900" height="89" alt="모니터링 최고 cpu p3" src="https://github.com/user-attachments/assets/27e43b20-8f1f-4555-8d12-b73b7e715be4" />
<br>

**부하 지속 시간**: 120초

---

#### 파티션 5개

**부하 시작점**

<img width="900" height="100" alt="p5 부하 시작점" src="https://github.com/user-attachments/assets/20994b61-bfb2-436a-b426-9425a53b6a07" />
<br>

**부하 꺾이는 시점**

<img width="900" height="329" alt="image" src="https://github.com/user-attachments/assets/22a6bb61-aab8-40a6-a7ea-726ebd6249f0" />

<br>

**최고 CPU**

<img width="900" height="95" alt="모니터링 최고 cpu 파티션 5" src="https://github.com/user-attachments/assets/1df62012-08a7-4f36-8b2f-aae61bd78bc9" />
<br>

**부하 지속 시간**: 128초

---

#### CPU 패턴 분석 결과

| 파티션 수 | CPU 패턴 | 체감 |
|-----------|----------|------|
| **1개** | 천천히 상승 → 완만하게 하강 | "점진적 처리" |
| **3개** | 시작하자마자 급상승 → 출렁이며 감소 | "처리 집중" |
| **5개** | 시작 즉시 최고치 → 빠른 하강 | "순간 폭발 후 정리" |

파티션 수 증가에 따라 Kafka Consumer 병렬성이 증가하면서,
처리 시작 시점에 여러 Consumer가 동시에 활성화되어 CPU 부하가 순간적으로 집중되는 현상이 관찰되었습니다.

그러나 **CPU 피크 구간이 짧아져도 전체 부하 지속 시간은 오히려 증가**했습니다.
이는 동시에 증가한 DB 접근으로 인해 **Row Lock 경합 및 트랜잭션 대기 시간**이 발생했기 때문입니다.

**결론**: 파티션 확장만으로는 성능 향상이 불가능 → **DB 병목 해결이 필수**

---

### 해결책: Redis 인메모리 재고 차감

| 구분 | Before (MySQL) | After (Redis) |
|------|----------------|---------------|
| **재고 차감** | `UPDATE ... SET stock = stock - 1` | `Redis DECR` (Lua 스크립트) |
| **동시성 처리** | Row Lock 경합 | 싱글 스레드 + 원자적 연산 |
| **I/O** | 디스크 | 인메모리 |

```lua
-- 원자적 재고 차감 Lua 스크립트
local stock = redis.call('GET', KEYS[1])
if stock == false then return -1 end
if tonumber(stock) > 0 then
    return redis.call('DECR', KEYS[1])
else
    return -1
end
```

---

## 실험 결과 개요

Redis 도입 후, Kafka 파티션 수에 따른 영향을 **세 가지 측정 기준**으로 분리하여 실험을 진행했습니다.

| 구분 | 측정 기준 | 의미 |
|------|-----------|------|
| Kafka TPS | Producer publish → Consumer poll | Kafka 파이프라인 처리 속도 |
| Web Monitoring | Kafka TPS + 진행률 + 재고 현황 | 운영 관점의 실시간 모니터링 |
| DB TPS | Consumer 처리 → DB transaction commit | 실제 비즈니스 처리 속도 |

**최종 성능 판단 기준은 DB transaction commit 기준(TPS)**으로 설정했습니다.
선착순 시스템에서 사용자에게 최종적으로 의미 있는 결과는 "요청이 실제로 처리되었는가"이기 때문입니다.

---

## DB Commit 기준 실험 결과 (최종 판단 기준)

**Consumer가 메시지를 처리한 뒤 DB 트랜잭션 커밋까지 완료한 시점**을 기준으로 측정한 결과입니다.
모든 실험은 **동일한 조건에서 100,000건 이벤트**를 처리하며 진행되었습니다.

| partition_count | total_events | processing_tps | switch_ratio | violation_count |
|-----------------|--------------|----------------|--------------|-----------------|
| 1 | 100,000 | 278.55 | 0 | 0 |
| 3 | 100,000 | **505.05** | 0.0023 | 0 |
| 5 | 100,000 | **595.24** | 0.0018 | 0 |

<img width="650" height="127" alt="db 결과" src="https://github.com/user-attachments/assets/f479fa02-e8ed-4d5c-bf8f-b4f1224ee1d4" />


### 지표 설명

| 지표 | 설명 |
|------|------|
| **processing_tps** | DB 트랜잭션 커밋까지 완료한 초당 처리 건수 |
| **switch_ratio** | 이전 이벤트와 파티션이 달라지는 비율 (높을수록 전역 순서 섞임) |
| **violation_count** | 동일 키 기준 offset 순서 역전 횟수 (0 = 키 기반 순서 보장) |

### 결과 해석

- **파티션 1개 → 3개**: TPS **1.8배 증가** (278 → 505)
- **파티션 1개 → 5개**: TPS **2.1배 증가** (278 → 595)
- **violation_count: 0** → 파티션 증가에도 키 기반 순서 보장 유지

Redis 도입으로 **DB 병목이 해결**되어 파티션 증가에 따른 **TPS 선형 증가**를 확인했습니다.

---

## Kafka 메시지 소비 기준 실험 (Kafka TPS)

**Producer publish 이후 Consumer poll 성공 시점**을 기준으로 측정한 결과입니다.
k6 부하 테스트 도구를 사용했습니다.

### 파티션 1개

<img width="250" height="330" alt="카프카 결과1" src="https://github.com/user-attachments/assets/b68e5cf5-af14-486b-a7ec-447e13ad5331" />


| 지표 | 값 |
|------|-----|
| 총 요청 | 100,000건 |
| TPS | 978.06 req/s |
| 평균 응답시간 | 1,970ms |
| P95 | 5,790ms |
| P99 | 14,540ms |
| 실패율 | 0% |

---

### 파티션 3개

<img width="265" height="345" alt="카프카p3" src="https://github.com/user-attachments/assets/326caa87-88e0-47cd-8164-bfd920be1fee" />


| 지표 | 값 |
|------|-----|
| 총 요청 | 100,000건 |
| TPS | 974.62 req/s |
| 평균 응답시간 | 1,960ms |
| P95 | 5,750ms |
| P99 | 10,080ms |
| 실패율 | 0% |

---

### 파티션 5개

<img width="215" height="369" alt="카프카p5" src="https://github.com/user-attachments/assets/967bf49d-2409-440e-9a54-68be57a4446a" />


| 지표 | 값 |
|------|-----|
| 총 요청 | 100,000건 |
| TPS | 968.06 req/s |
| 평균 응답시간 | 1,970ms |
| P95 | 6,420ms |
| P99 | 12,680ms |
| 실패율 | 0% |

---

## Web 실시간 모니터링 기준 분석

웹 대시보드에서 **Kafka TPS, 처리 진행률, 재고 현황**을 실시간으로 관찰한 결과입니다.

### 파티션 1개

![p1실시간](https://github.com/user-attachments/assets/512d5001-71a7-4651-9903-2926530a05c1)

<br>
<img width="500" height="638" alt="p1실시간" src="https://github.com/user-attachments/assets/6aec3bed-b9fe-41cf-bf39-99eb22b6f447" />
<br>

| 지표 | 값 |
|------|-----|
| 테스트 기간 | 366초 |
| 총 처리량 | 100,000건 |
| 평균 TPS | 271.50 req/s |
| 최고 TPS | 397.73 req/s |
| 성공 | 100,000건 |
| 실제 처리 TPS | 431.00 msg/s |

---
<br>

### 파티션 3개

![p3 실시간](https://github.com/user-attachments/assets/3592bae7-ef18-4834-8ad4-1acd1490c5aa)

<br>
<img width="500" height="633" alt="실시간 모니터링p3" src="https://github.com/user-attachments/assets/56b8f3a1-a980-48ac-8f19-0e1747c7d222" />
<br>

| 지표 | 값 |
|------|-----|
| 테스트 기간 | 203초 |
| 총 처리량 | 100,000건 |
| 평균 TPS | 492.70 req/s |
| 최고 TPS | **1,182.11 req/s** |
| 성공 | 100,000건 |
| 실제 처리 TPS | 411.00 msg/s |

---

### 파티션 5개

![p5 실시간](https://github.com/user-attachments/assets/288c1055-dc14-44ec-b4b9-38d2d05642af)

<br>
<img width="500" height="633" alt="실시간 모니터링p5" src="https://github.com/user-attachments/assets/ced25510-5ed5-4d3e-a193-9a7c4a2a187d" />
<br>

| 지표 | 값 |
|------|-----|
| 테스트 기간 | 168초 |
| 총 처리량 | 100,000건 |
| 평균 TPS | 595.24 req/s |
| 최고 TPS | **1,915.62 req/s** |
| 성공 | 100,000건 |
| 실제 처리 TPS | 413.75 msg/s |

---

## 실험 결론

| 파티션 수 | DB TPS | 테스트 시간 | 특징 |
|-----------|--------|-------------|------|
| 1개 | 278.55 | 366초 | 단일 스레드, 완벽한 순서 보장 |
| 3개 | **505.05** | 173초 | 1.8배 향상, 순서 보장 유지 |
| 5개 | **595.24** | 168초 | 2.1배 향상, 순서 보장 유지 |

**핵심 발견:**
- Redis 도입으로 DB 병목 해결 → 파티션 확장에 따른 TPS 선형 증가 확인
- violation_count 0 유지 → 파티션 증가에도 키 기반 순서 보장

### 최종 선택: 파티션 1개

| 옵션 | 장점 | 단점 |
|------|------|------|
| 파티션 3~5개 + Redis | TPS 2배 향상 (595 TPS) | 전역 순서 보장 불가 |
| **파티션 1개 + Redis** | 완벽한 순서 보장 | TPS 상대적으로 낮음 (278 TPS) |

**본 시스템은 파티션 1개를 선택했습니다.**

선착순 이벤트의 핵심 가치는 **"1등으로 요청한 사람이 1등으로 처리되는 공정성"**입니다.
처리량(TPS)은 Redis 도입과 스케일 아웃으로 보완할 수 있지만,
한 번 깨진 순서는 어떤 기술로도 복구할 수 없습니다.

**트레이드오프 결론:**
- 처리량보다 **공정성**이 비즈니스에서 더 중요
- 278 TPS도 일반적인 선착순 이벤트에는 충분한 수치
- 순서가 보장되지 않으면 사용자 신뢰 상실 → 서비스 가치 하락


---

## 배운 점 및 향후 개선 방향

이번 프로젝트를 통해 **10만 단위 트래픽 환경**에서 Kafka 파티션과 Redis를 활용해  
**처리량(Throughput)과 순서 보장(Fairness) 사이의 트레이드오프**를 정량적으로 분석하고,  
비즈니스 요구사항에 맞는 아키텍처를 선택하는 경험을 쌓을 수 있었습니다.

현재 아키텍처는 **Kafka 파티션 1개**를 기준으로 설계되어 있으며,  
**완벽한 공정성(요청 순서 = 처리 순서)**을 보장하면서  
초당 약 **278 TPS**의 요청을 안정적으로 처리합니다.

하지만 여기서 한 걸음 더 나아가,  
만약 **100만 단위의 동시 트래픽**이 몰리는 극단적인 상황을 가정한다면  
현재 구조의 한계를 넘어서는 새로운 고민이 필요합니다.

---

### 대기열 시스템 도입 (Waiting Queue)

현재 시스템은 유입되는 모든 요청을 **즉시 Kafka 토픽으로 발행**합니다.  
그러나 트래픽이 시스템의 처리 용량을 크게 초과할 경우,  
Kafka 브로커와 Consumer에 **과도한 Backpressure**가 집중되어  
시스템 전체가 불안정해질 수 있습니다.

#### 개선 아이디어

- **Redis 기반 대기열 도입**
  - API 서버 앞단에 Redis의 `LIST` 또는 `Sorted Set`을 활용한 대기열을 구성합니다.
  - 요청 흐름은 다음과 같습니다.
    1. 사용자의 요청이 들어오면, 즉시 Redis 대기열에 등록하고 **대기 번호**를 반환합니다.
    2. 별도의 스케줄러 또는 워커가 일정 주기마다  
       시스템이 감당 가능한 수준(예: 초당 300건)만큼만 대기열에서 꺼내어 Kafka로 이벤트를 발행합니다.

#### 기대 효과

- **안정적인 부하 제어**  
  시스템 처리 용량을 초과하는 요청을 앞단에서 흡수하여  
  Consumer와 DB를 보호합니다.
- **향상된 사용자 경험**  
  요청 실패 대신 "대기 중" 상태와 순번을 제공하여  
  사용자 이탈을 줄이고 예측 가능한 경험을 제공합니다.
- **공정한 순서 유지**  
  Redis 대기열에 들어온 순서대로 처리되므로  
  선착순의 공정성을 시스템 진입 단계부터 보장할 수 있습니다.

---



## 왜 파티션 1개인가: 순서 보장 관점

> Kafka 순서 보장의 핵심 조건과 멀티 파티션에서 순서가 깨지는 지점

### 완벽한 순서 보장 조건

Kafka에서 **발행 순서 = 처리 순서**를 보장하려면 다음 조건이 필요합니다.

| 조건 | 역할 |
|------|------|
| **단일 파티션** | 메시지가 offset 순서대로 저장됨 |
| **단일 Consumer 스레드** | offset 순서대로 poll → 처리 |
| **동기 처리** | 하나의 메시지 처리가 끝나야 다음 메시지 처리 |

본 시스템은 파티션 1개 환경에서 `concurrency = 1`로 설정하여 **단일 스레드가 순차적으로 처리**합니다.

```java
// KafkaConfig.java
factory.setConcurrency(partitionCount);  // 파티션 1개 → 스레드 1개

// ParticipationEventConsumer.java
for (ConsumerRecord<String, String> record : records) {
    processRecord(record);  // 순차 처리 (동기)
}
```

---

### 멀티 파티션에서 순서가 깨지는 두 가지 지점

파티션을 늘리면 순서가 깨지는 지점이 **두 군데** 발생합니다.

#### 1. Consumer Poll 단계 (Kafka → Consumer)

```
Partition 0: [A, B, C]  offset 순서대로 저장
Partition 1: [D, E, F]  offset 순서대로 저장
Partition 2: [G, H, I]  offset 순서대로 저장
           ↓
    Consumer Poll (3개 파티션에서 동시에 가져옴)
           ↓
    [A, D, G, B, E, H, ...]  ← 전역 순서 섞임
```

각 파티션 **내부**에서는 offset 순서가 유지되지만, **파티션 간** 메시지 순서는 보장되지 않습니다.

#### 2. DB 처리 단계 (Consumer → DB)

```
concurrency = 3 (파티션 수만큼 스레드 생성)

Thread-0: A 처리 중... → DB commit (3번째 완료)
Thread-1: D 처리 중... → DB commit (1번째 완료)
Thread-2: G 처리 중... → DB commit (2번째 완료)

→ DB 기록 순서: D → G → A (발행 순서와 무관)
```

멀티 스레드 환경에서는 **어떤 스레드가 먼저 DB commit을 완료하는지 예측할 수 없습니다.**

---

### switch_ratio 지표의 의미

실험 결과에서 측정한 `switch_ratio`는 위의 **첫 번째 지점**(Poll 단계)에서 발생한 순서 섞임을 수치화한 것입니다.

| 파티션 수 | switch_ratio | 해석 |
|-----------|--------------|------|
| 1개 | 0 | 파티션 전환 없음 (완벽한 순서) |
| 3개 | 0.0026 | 1000건 중 약 2.6건이 다른 파티션에서 옴 |
| 5개 | 0.0020 | 1000건 중 약 2건이 다른 파티션에서 옴 |

`switch_ratio`가 낮게 나온 이유는 각 파티션이 비교적 균등하게 메시지를 분배받아 **연속된 메시지가 같은 파티션에 몰리는 경향**이 있었기 때문입니다.

하지만 이 수치가 낮더라도, 멀티 스레드 처리 단계에서 **DB commit 순서는 여전히 예측 불가능**합니다.

---

### 파티션 1개 선택의 기술적 근거

| 구분 | 멀티 파티션 (3~5개) | 단일 파티션 (1개) |
|------|---------------------|-------------------|
| **Poll 순서** | 파티션 간 섞임 발생 | offset 순서 그대로 |
| **처리 스레드** | 멀티 스레드 (concurrency = N) | 단일 스레드 (concurrency = 1) |
| **DB commit 순서** | 예측 불가능 | 처리 순서 = commit 순서 |
| **순서 보장** | 불가능 | 완벽 보장 |

선착순 이벤트에서 "**1등으로 요청한 사람이 1등으로 처리되어야 한다**"는 요구사항을 만족시키려면, 단일 파티션 + 단일 스레드 구조가 필수입니다.

---

## DB 원자성 보장

> Kafka가 순서를 '완화'시켜도, 최종 결과는 틀리지 않게 만든 장치

파티션이 몇 개든, 순서가 조금 섞이든 **재고는 절대 초과되지 않습니다.**

### 원자적 재고 차감 쿼리

```sql
UPDATE campaign
SET current_stock = current_stock - 1
WHERE id = :id AND current_stock > 0;
```

### 왜 이 방식이 안전한가?

| 방식 | 문제점 |
|------|--------|
| SELECT 후 UPDATE | 조회와 차감 사이에 다른 요청이 끼어들 수 있음. 명시적 락(@Lock) 필요 |
| 원자적 UPDATE | 조건 + 차감을 하나의 SQL로 수행. DB가 자동으로 Row Lock 처리, 명시적 락 불필요 |

**결과 해석**:
- `affected rows = 1`: 재고 차감 성공 (SUCCESS)
- `affected rows = 0`: 이미 소진됨 (FAIL)

Kafka에서 전역 순서가 다소 섞이더라도 DB 레벨에서 원자적으로 재고를 검증하기 때문에, 최종 결과의 정합성은 항상 보장됩니다.

---

## Dead Letter Queue (DLQ)

> Consumer 처리 실패 시 메시지 유실을 방지하는 안전장치

### 왜 DLQ가 필요한가?

Kafka Consumer에서 메시지 처리 중 예외가 발생하면 두 가지 문제가 생깁니다.

| 상황 | 문제점 |
|------|--------|
| **예외 무시** | 메시지 유실, 참여 이력 누락 |
| **무한 재시도** | 동일 메시지가 계속 실패하며 전체 처리 차단 |

DLQ는 **처리 불가능한 메시지를 별도 토픽으로 격리**하여 정상 메시지 처리를 계속하면서도 실패 메시지를 보존합니다.

---

### DLQ 처리 흐름

```
Consumer 메시지 처리
    ├── 성공 → DB 저장 → offset commit
    └── 실패 → DLQ 토픽 전송 → offset commit (재처리 방지)
```

### DLQ 메시지 구조

```json
{
  "originalMessage": "원본 Kafka 메시지",
  "errorReason": "CampaignNotFoundException",
  "errorMessage": "캠페인을 찾을 수 없습니다: 999",
  "errorType": "CampaignNotFoundException",
  "timestamp": "2026-01-20T12:34:56"
}
```

---

### 구현 방식

| 구분 | 처리 |
|------|------|
| **단일 메시지 오류** | JSON 파싱 실패, 캠페인 없음 등 → 개별 DLQ 전송 |
| **배치 전체 오류** | DB 연결 실패 등 심각한 오류 → 배치 전체 DLQ 전송 |

```java
// ParticipationEventConsumer.java
private static final String DLQ_TOPIC = "campaign-participation-topic.dlq";

private void sendToDlq(String originalMessage, String errorReason, Exception exception) {
    Map<String, Object> dlqMessage = new HashMap<>();
    dlqMessage.put("originalMessage", originalMessage);
    dlqMessage.put("errorReason", errorReason);
    dlqMessage.put("errorMessage", exception.getMessage());
    dlqMessage.put("timestamp", LocalDateTime.now().toString());

    kafkaTemplate.send(DLQ_TOPIC, jsonMapper.writeValueAsString(dlqMessage));
}
```

---

### DLQ 도입 효과

| 항목 | 효과 |
|------|------|
| **메시지 유실 방지** | 실패 메시지도 DLQ에 보존되어 추후 분석 가능 |
| **장애 격리** | 잘못된 메시지가 전체 처리를 차단하지 않음 |
| **디버깅 용이** | 원본 메시지 + 에러 정보가 함께 저장되어 원인 파악 가능 |
| **운영 안정성** | 정상 메시지는 계속 처리되어 서비스 영향 최소화 |

---

## 배치 처리

> 실시간과 집계를 분리한 이유

실시간 트래픽 경로에는 집계 로직을 두지 않습니다. 통계는 **지연 허용 가능**하기 때문에 Spring Batch로 분리했습니다.

### 왜 배치로 분리했는가?

| 구분 | 실시간 처리 | 배치 처리 |
|------|-------------|-----------|
| **목적** | 재고 차감, 참여 기록 | 일별 통계 집계 |
| **응답 시간** | ms 단위 필수 | 지연 허용 |
| **실행 시점** | 즉시 | 새벽 2시 |
| **부하 영향** | 사용자 경험 직결 | 시스템 유휴 시간 활용 |

실시간 경로에서 매번 `COUNT(*) GROUP BY`를 실행하면 트래픽 폭주 시 DB 부하가 급증합니다.
배치로 분리하면 **피크 타임 부하를 회피**하고, 집계 로직 변경 시에도 실시간 경로에 영향을 주지 않습니다.

---

### 배치 설계

| 항목 | 내용 |
|------|------|
| **실행 시간** | 매일 새벽 2시 자동 실행 (`@Scheduled`) |
| **집계 대상** | `participation_history` → `campaign_stats` |
| **N+1 해결** | GROUP BY 단일 쿼리로 모든 캠페인 일괄 집계 |
| **멱등성 보장** | `ON DUPLICATE KEY UPDATE` |
| **메타데이터 정리** | 매주 일요일 새벽 3시, 90일 이상 이력 삭제 |

---

### 단일 쿼리 집계 (N+1 문제 해결)

캠페인이 100개든 1,000개든 **쿼리 1번**으로 모든 캠페인의 통계를 집계합니다.

```sql
INSERT INTO campaign_stats (campaign_id, success_count, fail_count, stats_date)
SELECT p.campaign_id,
       SUM(CASE WHEN p.status = 'SUCCESS' THEN 1 ELSE 0 END),
       SUM(CASE WHEN p.status = 'FAIL' THEN 1 ELSE 0 END),
       DATE(:start)
FROM participation_history p
WHERE p.created_at >= :start AND p.created_at < :end
GROUP BY p.campaign_id
ON DUPLICATE KEY UPDATE
  success_count = VALUES(success_count),
  fail_count = VALUES(fail_count);
```

**핵심 설계 로직**:
- `GROUP BY campaign_id`: 모든 캠페인을 한 번에 집계
- `ON DUPLICATE KEY UPDATE`: 같은 날짜를 여러 번 집계해도 데이터 중복 없음 (멱등성)
- `(campaign_id, stats_date)` 복합 Unique 제약조건으로 중복 방지

---

### 배치 실행 결과

<img width="614" height="606" alt="배치성공 1월15" src="https://github.com/user-attachments/assets/25f3b5e0-3330-4b67-9fd0-21ea4b529a52" />

### 결과 해석

위 대시보드는 **2026-01-15** 배치 실행 후 집계된 통계입니다.

| 지표 | 값 | 의미 |
|------|-----|------|
| **총 참여** | 701,000건 | 하루 동안 처리된 전체 이벤트 수 |
| **총 캠페인** | 8개 | 집계 대상 캠페인 수 |
| **총 성공** | 700,298건 | 재고 차감 성공 건수 |
| **총 실패** | 702건 | 재고 소진으로 인한 실패 건수 |
| **전체 성공률** | 99.90% | 시스템 처리 정확도 |

**캠페인별 분석**:

- **100k 시리즈 캠페인** (100k-p2-v2, 100k-p2-v3, p3-v1, p5, p5-v2, p10-v1)
  - 각각 100,000건 참여, **100% 성공률**
  - 재고가 충분한 상태에서 모든 요청이 정상 처리됨

- **스프링 배치 테스트 캠페인**
  - 1,000건 참여 중 298건 성공, 702건 실패 (**29.80% 성공률**)
  - 이는 시스템 오류가 아닌 **정상적인 재고 소진**
  - 선착순 시스템에서 재고(298개)가 소진된 후 나머지 요청은 `FAIL` 처리



실패 702건은 모두 **"스프링 배치 테스트" 캠페인**에서 발생했으며,
이는 원자적 재고 차감 쿼리(`UPDATE ... WHERE current_stock > 0`)가
재고 소진 후 요청을 정확히 거부했음을 의미합니다.

즉, **99.90% 성공률**은 시스템의 높은 안정성을,
**702건 실패**는 재고 초과 판매 방지 로직이 정상 작동했음을 증명합니다.


---

## 클라우드 아키텍처

> 실제 AWS 환경에서 운영

### AWS 서비스 구성

| 분류 | 서비스 | 용도 |
|------|--------|------|
| **Compute** | EC2 (App Server) | API 서버 + Consumer + Batch |
| **Compute** | EC2 (Kafka) | Kafka 브로커 (Docker) |
| **Database** | RDS (MySQL) | 참여 이력 저장 |
| **Cache** | ElastiCache (Redis) | 인메모리 재고 차감 |
| **Network** | ALB | 트래픽 분산, 헬스체크 |
| **CI/CD** | GitHub Actions | 빌드 및 배포 자동화 |
| **CI/CD** | CodeDeploy | EC2 무중단 배포 |
| **Storage** | S3 | CodeDeploy 배포 번들(zip) 저장 |
| **Container** | ECR | Docker 이미지 저장소 |
| **Container** | Docker | Kafka 컨테이너 실행 |
| **Security** | Parameter Store | 환경변수 및 시크릿 관리 |
| **Access** | SSM Session Manager | SSH 없이 EC2 접속 |


### 인프라 구성

| 구성 요소 | 설명 |
|-----------|------|
| **EC2 (App)** | Spring Boot 애플리케이션 (API + Consumer + Batch) |
| **EC2 (Kafka)** | Docker로 Kafka 브로커 운영 |
| **RDS** | MySQL 8.0, 재고 및 참여 이력 저장 |
| **ALB** | HTTPS 처리, 헬스체크, 트래픽 분산 |
| **S3** | CodeDeploy 배포용 zip 번들 저장 |


<img width="1011" height="483" alt="image" src="https://github.com/user-attachments/assets/597f4e50-9b71-43aa-ae80-1ad69640ef79" />

---

## 아키텍처 상세 설명

본 아키텍처는 **대용량 트래픽 처리 및 배치 집계**를 안정적으로 수행하면서도, 불필요한 관리형 서비스를 배제하여 **비용을 최소화**하는 방향으로 설계되었습니다.

### 배포 파이프라인 (CI/CD) — Blue-Green 전략

대용량 트래픽 환경에서 배포 중 리소스 간섭을 방지하고 서비스 중단 시간을 0으로 유지하기 위해 **Blue-Green** 방식을 채택합니다.

* **GitHub Actions (CI):** * Docker 이미지를 빌드하여 **ECR**에 Push합니다.
* 배포 스크립트 및 설정 파일(`appspec.yml`)을 `deploy.zip`으로 압축하여 **S3**에 업로드합니다.


* **CodeDeploy (Orchestration):** * 배포 시점에만 새로운 **Green EC2 인스턴스**를 생성하여 비용 낭비를 최소화합니다 (On-Demand Scaling).
* 새 인스턴스의 **CodeDeploy Agent**에게 배포 명령을 내립니다.


* **Green EC2 인스턴스 (실행 주체):**
* **fetch revision:** S3에서 `deploy.zip`을 다운로드합니다.
* **execute hooks:** `appspec.yml`에 정의된 훅에 따라 배포 전후 처리를 수행합니다.
* **docker pull:** **ECR**로부터 최신 이미지를 직접 당겨와 컨테이너를 실행합니다.


* **교체 및 회수:** ALB가 Green 인스턴스의 헬스체크를 완료하면 트래픽을 전환하고, 기존 Blue 인스턴스는 즉시 제거하여 비용을 절감합니다.

---

### 런타임 아키텍처 및 네트워크 구성

비용 절감을 위해 NAT Gateway와 같은 고비용 컴포넌트를 의도적으로 배제하였습니다.

* **Public Subnet (ALB, API EC2, Kafka EC2):** * **ALB:** 외부 트래픽의 유일한 진입점으로 트래픽 분산 및 헬스체크를 담당합니다.
* **API EC2 (Spring Boot):** 8080/8081 포트에서 동작하며 API, Consumer, Batch 역할을 통합 수행합니다.
* **Native Kafka EC2:** Docker 없이 EC2에 직접 설치하여 컨테이너 오버헤드를 제거하고 대용량 트래픽 처리 성능을 극대화했습니다. 고정 인프라로 운영되며 ALB와 연결되지 않습니다.


* **Private Subnet (RDS):** * **MySQL 8.0:** 재고 및 이력을 저장하며, 오직 API EC2의 보안 그룹을 통해서만 접근을 허용하여 보안을 강화했습니다.
* **비용 최적화:** 모든 EC2를 Public Subnet에 배치하여 **NAT Gateway 비용을 제거**하고, Outbound 트래픽은 Internet Gateway를 통해 직접 처리합니다.

---

### 보안 및 관리 전략 (SSM & Parameter Store)

인프라의 복잡도를 낮추면서도 보안 표준을 준수합니다.

* **SSM (Systems Manager) Session Manager:** * 22번 포트(SSH)를 완전히 차단하고 SSM을 통해 EC2에 접속하여 키 파일 관리 부담을 없애고 보안 사고를 예방합니다.
* **Parameter Store:** * DB 계정 정보 및 Kafka 설정 등 모든 민감 정보를 중앙에서 관리하여 소스 코드 내 유출을 원천 차단합니다.
* **보안 그룹 (Security Group):**
* **API EC2:** ALB로부터의 8080/8081 트래픽만 허용합니다.
* **Kafka EC2:** API EC2로부터의 9092 포트 내부 통신만 허용합니다.
* **RDS:** API EC2로부터의 3306 포트 접근만 허용합니다.



---

### 설계 요약

1. **비용 최소화:** NAT Gateway 미사용, 배포 시점에만 자원 이중화, MSK 대신 Native Kafka 직접 운영.
2. **성능 최적화:** Native Kafka를 통한 고처리량 확보 및 Blue-Green을 통한 배포 중 성능 간섭 배제.
3. **운영 안정성:** SSM을 통한 안전한 접근과 Parameter Store를 통한 보안 설정 관리.

---

## Author

**HSKHSMM** , **leepg038292**

---

이 프로젝트는 "기술을 써봤다"가 아니라 "왜 이 기술을 선택했는지 설명할 수 있는 구조"를 목표로 합니다.

**파티션 개수에 따른 순서 보장 vs 성능 트레이드오프**를 정량적으로 증명하고, 이를 바탕으로 아키텍처 결정을 내린 과정을 담았습니다.
