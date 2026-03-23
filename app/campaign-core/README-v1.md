# Event-Driven First-Come-First-Served Campaign System

> **10만 건 동시 트래픽 환경에서 공정한 선착순 처리를 보장하는 이벤트 시스템**
>
> *This project focuses on architectural decision-making through measurable experiments rather than feature completeness.*

[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.1-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Kafka](https://img.shields.io/badge/Apache%20Kafka-3.x-black.svg)](https://kafka.apache.org/)
[![MySQL](https://img.shields.io/badge/MySQL-8.0-blue.svg)](https://www.mysql.com/)

---

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
| **Database** | MySQL 8.0 | 트랜잭션 보장 + 원자적 UPDATE |
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

## 실제 실험 결과 개요

본 프로젝트에서는 Kafka 파티션 수에 따른 영향을 단일 TPS 지표로 판단하지 않고,  
**측정 기준을 세 가지로 분리하여 실험을 진행**했습니다.

Kafka 시스템은 처리 구간에 따라 병목 지점과 의미가 달라지기 때문에,  
각 측정 기준은 서로 다른 관점을 제공합니다.

### 실험에서 사용한 측정 기준

| 구분 | 측정 기준 | 의미 |
|------|-----------|------|
| Kafka TPS | Producer publish → Consumer poll | Kafka 파이프라인 처리 속도 |
| Web Monitoring | Kafka TPS + CPU + 진행률 | 운영 관점의 부하 분포 |
| DB TPS | Consumer 처리 → DB transaction commit | 실제 비즈니스 처리 속도 |

이 중 **최종 성능 판단 기준은 DB transaction commit 기준(TPS)**으로 설정했습니다.  
그 이유와 근거는 아래 실험 결과를 통해 설명합니다.

---

## DB Commit 기준 실험 결과 (최종 판단 기준)

다음 표는 **Consumer가 메시지를 처리한 뒤 DB 트랜잭션 커밋까지 완료한 시점**을 기준으로  
초당 처리량(TPS)을 측정한 결과입니다.

모든 실험은 **동일한 조건에서 100,000건 이벤트**를 처리하며 진행되었습니다.

| partition_count | total_events | processing_tps | switch_ratio | violation_count |
|-----------------|--------------|----------------|--------------|-----------------|
| 1 | 100,000 | 285.71 | 0 | 0 |
| 3 | 100,000 | 279.33 | 0.0026 | 0 |
| 5 | 100,000 | 288.18 | 0.0020 | 0 |
<img width="944" height="151" alt="Kafka 파티션 수 증가에 따른 처리 성능과 순서 특성 요약" src="https://github.com/user-attachments/assets/d8eadf7c-7b8d-4293-b416-da94920f5088" />


### 지표 설명 (Metrics Description)

**partition_count**  
- Kafka 토픽에 설정한 파티션 개수입니다.

**total_events**  
- 실험에서 처리한 전체 이벤트 수입니다.

**processing_tps**  
- Consumer가 메시지를 처리하고 **DB 트랜잭션 커밋까지 완료한 초당 처리 건수(TPS)**입니다.  
- Kafka 내부 처리 속도가 아닌, **실제 비즈니스 처리량 기준**의 TPS입니다.

**switch_ratio**  
- Kafka 이벤트를 시간 순서(kafka_timestamp)로 정렬했을 때, 이전 이벤트와 파티션이 달라지는 비율입니다.  
- 값이 높을수록 전역 이벤트 순서가 더 자주 섞였음을 의미합니다.

**violation_count**  
- 동일한 키(user_id) 기준으로 Kafka offset 순서가 역전된 경우의 수입니다.  
- 이 값이 0이라는 것은, 파티션 수와 무관하게 키 기반 순서 보장이 유지되었음을 의미합니다.

---

## DB Commit 기준 결과 해석

- 파티션 수를 1 → 3 → 5로 증가시켜도  
  **DB commit 기준 TPS는 선형적으로 증가하지 않았습니다.**
- 파티션 1, 3, 5 모두 **유사한 처리량 범위(약 280 TPS 내외)**를 보였습니다.
- 이는 시스템의 병목이 Kafka가 아닌  
  **Consumer 이후 DB 처리 단계에 있음을 의미**합니다.

즉, Kafka는 더 많은 메시지를 동시에 전달할 수 있었지만,  
DB가 이를 지속적으로 처리하지는 못했습니다.

---

## Kafka 메시지 소비(= Consumer가 Kafka에서 메시지를 poll로 읽어오는 행위) 기준 실험 (Kafka TPS)

다음은 **Producer publish 이후 Consumer poll 성공 시점**을 기준으로 측정한 결과입니다.  
이 측정은 **Kafka 파이프라인 자체의 처리 능력**을 보여줍니다.

이 구간에서는 DB 처리 여부와 무관하게,  
Kafka가 얼마나 빠르게 메시지를 전달하는지를 확인할 수 있습니다.

### 파티션별 Kafka 메시지 소비 흐름

#### 파티션 1개
- 단일 Consumer 흐름
- 메시지 소비 속도가 비교적 일정
- 처리 흐름이 예측 가능

<p align="center">
  <img src="https://github.com/user-attachments/assets/961e9229-bb2d-425e-96e0-a4881a0f12ea" width="700" />
</p>


---

#### 파티션 3개
- Consumer 병렬성 증가
- 초반 Kafka TPS 상승
- 메시지 소비 시작 속도 증가

<p align="center">
  <img src="https://github.com/user-attachments/assets/8cedcacf-cfe4-414d-aab6-1fda25f67cf4" width="700" />
</p>


---

#### 파티션 5개
- 가장 많은 Consumer가 동시에 동작
- 메시지 소비가 초반에 집중됨
- Kafka 파이프라인 기준 처리 속도는 가장 빠름

<p align="center">
  <img src="https://github.com/user-attachments/assets/4e13d2b5-e65c-4ee5-a57f-02ce4d84ea64" width="700" />
</p>


---

## Web 실시간 모니터링 기준 분석

웹 대시보드는 Kafka TPS, 처리 진행률, CPU 사용률을  
**운영 관점에서 실시간으로 관찰하기 위해 구성**되었습니다.

이 지표는 처리량 자체보다는  
**부하가 언제, 어떻게 발생하는지를 시각적으로 보여주는 역할**을 합니다.

### 파티션별 Web 모니터링 결과

#### 파티션 1개

![p1 monitor2](https://github.com/user-attachments/assets/217f5ac7-6a81-4294-8b9f-ca1ebb7797b8)


- CPU 부하가 서서히 상승
- 처리 전반에 걸쳐 안정적인 패턴
- 부하 분산이 완만함

<img width="796" height="801" alt="KakaoTalk_20260114_160240952" src="https://github.com/user-attachments/assets/b7364a43-6dba-4875-8ebe-9dabbfad2f38" />


---

#### 파티션 3개

![p3 monitor](https://github.com/user-attachments/assets/2754fd82-474f-472c-b5e1-a4499d468a98)


- 메시지 소비가 더 빠르게 시작
- CPU 부하가 비교적 이른 시점에 상승
- 초반 부하 집중 현상 관측

<img width="796" height="801" alt="KakaoTalk_20260115_180433523" src="https://github.com/user-attachments/assets/1757a66d-4b4a-4aec-a7be-fbdcdd495ba6" />


---

#### 파티션 5개

![p5 monitor4](https://github.com/user-attachments/assets/906edf8d-52c1-4fc5-bd7e-9dfaf6f2b318)


- 메시지 소비가 초반에 가장 집중
- CPU 사용률이 빠르게 피크에 도달
- 전체 처리 완료 시점은 큰 차이 없음

<img width="796" height="801" alt="KakaoTalk_20260115_193853573" src="https://github.com/user-attachments/assets/4c478281-7b02-4eb0-958b-a98b543bd1c7" />


---

## 중간 결론: 측정 기준에 따라 달라지는 해석

- Kafka TPS 기준에서는 파티션 수 증가에 따른 **명확한 처리 속도 차이**가 존재합니다.
- Web 모니터링 기준에서는 파티션 증가가  
  **부하 발생 시점과 분포를 앞당기는 효과**를 보였습니다.
- 그러나 DB commit 기준에서는  
  **실제 처리량이 거의 증가하지 않았습니다.**

따라서 본 프로젝트에서는  
**DB transaction commit 기준을 최종 성능 판단 지표로 채택**했습니다.

이는 선착순 시스템에서 사용자에게 최종적으로 의미 있는 결과는
“요청이 실제로 처리되었는가”이기 때문입니다.


### 진짜 차이: 부하 패턴

가장 명확한 차이는 **초반 부하 패턴**에서 나타났습니다.

| 파티션 수 | CPU 상승 패턴 | 특징 |
|-----------|---------------|------|
| 1개 | 서서히 상승 | 완만한 부하 분산 |
| 3개 | 빠르게 상승 | 조기 포화 |
| 5개 | 더 빠르게 상승 | 초반 집중 부하 |

파티션이 증가하면 Consumer 스레드가 더 동시에 깨어나고, 메시지를 더 빠르게 가져와서 **DB에 더 이른 시점에 부하가 집중**됩니다.


---


#### 파티션 1개

**부하 시작점**

<img width="1096" height="125" alt="p1 부하 시작점" src="https://github.com/user-attachments/assets/1bcf617f-ea1b-43f4-ae47-88c122f27f85" />
<br>

**부하 꺾이는 시점**

<img width="1117" height="432" alt="image" src="https://github.com/user-attachments/assets/0d8facc0-c582-4794-8ac3-2261c78a09e2" />

<br>

**최고 CPU**

<img width="1129" height="123" alt="모니터링 최고 cpu" src="https://github.com/user-attachments/assets/27de7fcc-3ea4-4c2a-839e-d270cbd08b4b" />
<br>

**부하 지속 시간**: 100초

---

#### 파티션 3개

**부하 시작점**

<img width="1117" height="125" alt="p3 부하 시작점" src="https://github.com/user-attachments/assets/a55cdbd3-38e5-4f26-8209-660a857cda35" />
<br>

**부하 꺾이는 시점**

<img width="1105" height="450" alt="image" src="https://github.com/user-attachments/assets/a2c895db-5f58-4d84-ae65-d670b42057f2" />

<br>

**최고 CPU**

<img width="1218" height="120" alt="모니터링 최고 cpu p3" src="https://github.com/user-attachments/assets/27e43b20-8f1f-4555-8d12-b73b7e715be4" />
<br>

**부하 지속 시간**: 120초

---

#### 파티션 5개

**부하 시작점**

<img width="1122" height="125" alt="p5 부하 시작점" src="https://github.com/user-attachments/assets/20994b61-bfb2-436a-b426-9425a53b6a07" />
<br>

**부하 꺾이는 시점**

<img width="1192" height="490" alt="image" src="https://github.com/user-attachments/assets/22a6bb61-aab8-40a6-a7ea-726ebd6249f0" />

<br>

**최고 CPU**

<img width="1161" height="122" alt="모니터링 최고 cpu 파티션 5" src="https://github.com/user-attachments/assets/1df62012-08a7-4f36-8b2f-aae61bd78bc9" />
<br>

**부하 지속 시간**: 128초


| 파티션 수  | CPU 패턴               | 체감            |
| ------ | -------------------- | ------------- |
| **1개** | 천천히 상승 → 완만하게 하강     | "점진적 처리" |
| **3개** | 시작하자마자 급상승 → 출렁이며 감소 | "처리 집중"    |
| **5개** | 시작 즉시 최고치 → 빠른 하강    | "순간 폭발 후 정리"     |

<p></p>
파티션 수 증가에 따라 Kafka Consumer 병렬성이 증가하면서,<br>
처리 시작 시점에 여러 Consumer가 동시에 활성화되어 CPU 부하가 순간적으로 집중되는 현상이 관찰되었습니다.<br>
이후 메시지 backlog가 빠르게 소진되며 CPU 사용률은 명확하게 해소되는 패턴을 확인하였습니다.
<br><br>
파티션 1개 환경에서는 Consumer가 단일 스레드로 직렬 처리되며,<br>
CPU 사용률이 높은 상태로 비교적 오래 유지되었으나,<br>
DB 접근이 직렬화되어 전체 처리 시간은 상대적으로 짧았습니다.<br>
<br>
반면 파티션 3개 및 5개 환경에서는 CPU 피크 구간은 짧았으나,<br>
동시에 증가한 DB 접근으로 인해 락 경합 및 트랜잭션 대기 시간이 발생하며<br>
전체 부하 지속 시간이 상대적으로 길어지는 경향을 확인하였습니다.

---

### 실험 결론

실험 결과, Kafka 파티션 수를 증가시켜도 처리량이 선형적으로 증가하지는 않았으며, 전역 순서 섞임 또한 극적으로 증가하지는 않았습니다.

다만 파파티션 수가 늘어날수록 Kafka 메시지 소비(poll)가 초반에 집중되어 더 빠르게 완료되지만, **CPU 부하가 더 빠르게 상승하는 패턴**을 확인할 수 있었습니다.

이는 Kafka 자체의 처리 성능보다는 **Consumer 이후 DB 처리 단계가 시스템 전체 처리량의 병목으로 작용했기 때문**입니다. 

즉, Kafka는 더 빠르게 이벤트를 전달할 수 있었지만, DB가 그 속도를 지속적으로 수용하지는 못했습니다.

본 시스템에서는 파티션 증가로 인한 성능 이득이 제한적인 상황에서, 선착순 이벤트의 **공정성과 예측 가능한 처리 흐름**을 우선하여 **파티션 1개 구성을 최종 선택**했습니다.



| 구분 | 내용 |
|------|------|
| **증명하지 못한 것** | 파티션 증가 시 TPS 선형 증가, 순서 심각한 붕괴 |
| **실제로 증명한 것** |파티션별 최적화를 해도 TPS 향상 제한적, 부하 패턴 차이 존재 |
| **최종 선택 근거** | 성능 이득 제한적 → 공정성(전역 순서 단순화) 우선 |

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
| **Database** | RDS (MySQL) | 재고 관리, 이력 저장 |
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
