# 1Million Campaign Orchestration System

> **100만 트래픽 선착순 이벤트 시스템 — AI 자율 운영 아키텍처**
>
> *[event-driven-batch-kafka-system (v1, 10만 트래픽)](https://github.com/hskhsmm/event-driven-batch-kafka-system) 의 확장 프로젝트입니다.*

[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.4-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Kafka](https://img.shields.io/badge/Apache%20Kafka-3.x_Cluster-black.svg)](https://kafka.apache.org/)
[![Terraform](https://img.shields.io/badge/Terraform-IaC-purple.svg)](https://www.terraform.io/)

---

## 프로젝트 개요

v1에서 증명한 **Kafka 기반 선착순 처리 구조**를 기반으로,
100만 트래픽 환경에서도 **초과 판매 0건**을 유지하는 것을 목표로 합니다.

단순한 스케일업이 아닌, 다음 세 가지 방향으로 고도화합니다.

- **인프라 코드화** — 수동 콘솔 조작 없이 Terraform으로 전체 AWS 인프라 관리
- **처리 구조 개선** — Redis Lua 즉시컷 + PENDING 선점으로 DB 부하 사전 차단
- **AI 자율 운영** — MCP 서버를 통해 Claude가 시스템 상태를 판단하고 운영 결정

---

## v1 → v2 비교

| 항목 | v1 (10만 트래픽) | v2 (100만 트래픽) |
|---|---|---|
| 트래픽 | 10만 건 | **100만 건** |
| 초과 판매 | 0건 | **0건 (동일)** |
| Redis | ElastiCache 단일 | **ElastiCache Cluster (3샤드)** |
| Kafka | EC2 단일 브로커 | **EC2 3-broker 클러스터** |
| 인프라 관리 | 수동 콘솔 | **Terraform IaC** |
| 운영 자동화 | 없음 | **AI(Claude MCP) 자율 운영** |

---

## 설계 철학

```
Redis   → 빠른 컷 + 스파이크 방지 내부 버퍼  (성능의 원천)
Kafka   → 영속 로그                          (진실의 원천, 재처리 기준)
DB      → 최종 확정                          (상태의 원천)
PENDING → 자리 선점                          (장애 복구의 기준점)
```

---

## 레포지토리 구조

```
1milion-campaign-orchestration-system/
├── app/
│   └── campaign-core/        # Spring Boot 애플리케이션
│       ├── src/
│       ├── deploy/           # CodeDeploy 스크립트 + docker-compose
│       └── .github/workflows/deploy.yml
├── infra/                    # Terraform (Phase 1)
├── mcp-server/               # Python/FastAPI AI 운영 서버 (Phase 3)
└── stress-test/              # k6 부하 테스트 스크립트
```

---

## Tech Stack

| 분류 | 기술 |
|------|------|
| **Language** | Java 25 (Virtual Thread) |
| **Framework** | Spring Boot 4.0.4, Thymeleaf (어드민 대시보드) |
| **Message Queue** | Apache Kafka (3-broker 클러스터) |
| **Cache** | Redis ElastiCache Cluster (3샤드) |
| **Database** | MySQL 8.0 (RDS) |
| **Batch** | Spring Batch |
| **Container** | Docker, Amazon ECR |
| **IaC** | Terraform |
| **AI 운영** | Claude API + MCP (Python/FastAPI) |
| **알림 / 승인** | Slack Webhook (AI 고위험 작업 사람 승인) |
| **감사 로그** | AWS DynamoDB |
| **시크릿 관리** | AWS SSM Parameter Store |
| **Load Test** | k6 |
| **Monitoring** | Prometheus + Grafana |
| **Metrics** | Micrometer (Spring Boot Actuator 연동) |
| **Cloud** | AWS (EC2, RDS, ElastiCache, ALB, CodeDeploy, SSM) |
| **CI/CD** | GitHub Actions + CodeDeploy (Blue-Green) |

---

## 개발 단계

- **Phase 1** — Terraform으로 전체 AWS 인프라 코드화
- **Phase 2** — Redis Cluster, Kafka 3-broker, Spring Boot 코드 고도화
- **Phase 3** — MCP 서버 구축 + AI 오케스트레이션 파이프라인

---

## Author

**HSKHSMM** , **leepg038292**
