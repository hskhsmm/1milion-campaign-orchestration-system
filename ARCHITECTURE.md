# 내맘대로캠페인: 현재 코드 구조 안내

> 2026-10-13 저장소 코드 기준의 짧은 길잡이. 배포된 AWS 환경과 일치하는지는 별도 확인이 필요하다. 2026-05의 상세 설계·실험 기록은 [보관 문서](docs/archive/2026-05-architecture.md)에 있다.

## 참여 요청에서 DB 반영까지

```text
POST /api/campaigns/{id}/participation
  → RateLimitService
  → Redis Lua: 캠페인 활성·중복·대기열·재고 확인, 재고 차감, 순번 발급, Queue 적재
  → HTTP 202 Accepted

Redis Queue
  → ParticipationBridge: Queue → Redis in-flight 원자 이동, Kafka 확인 후 in-flight 제거
  → Kafka campaign-participation-topic
  → ParticipationEventConsumer: batch INSERT/단건 폴백, ack 또는 실패 처리
  → MySQL participation_history
```

`202`는 Redis에서 요청을 접수했다는 뜻이지, Kafka 저장이나 DB 반영 완료를 뜻하지 않는다. 이 단계별 처리량과 지연을 섞어 해석하지 않는다.

| 구간 | 현재 구현 근거 |
| --- | --- |
| HTTP 접수 | [ParticipationController](app/campaign-core/src/main/java/io/eventdriven/campaign/api/controller/ParticipationController.java), [ParticipationService](app/campaign-core/src/main/java/io/eventdriven/campaign/application/service/ParticipationService.java) |
| Redis 원자 처리 | [RedisStockService](app/campaign-core/src/main/java/io/eventdriven/campaign/application/service/RedisStockService.java), [Lua 스크립트](app/campaign-core/src/main/resources/scripts/check-decr-enqueue.lua) |
| Redis → Kafka | [ParticipationBridge](app/campaign-core/src/main/java/io/eventdriven/campaign/application/bridge/ParticipationBridge.java), [KafkaConfig](app/campaign-core/src/main/java/io/eventdriven/campaign/config/KafkaConfig.java) |
| Kafka → DB | [ParticipationEventConsumer](app/campaign-core/src/main/java/io/eventdriven/campaign/application/consumer/ParticipationEventConsumer.java) |

Bridge는 각 실행 인스턴스에서 활성 캠페인 집합을 순회한다. 캠페인별 Queue와 `bridge:inflight:campaign:{id}`는 같은 Redis Cluster 슬롯이며, Lua가 원자적으로 이동한다. Kafka 발행 성공 콜백에서만 해당 claim을 제거한다. 프로세스 종료·발행 실패로 남은 claim은 5분 뒤 다시 Queue로 돌아간다. 재시도로 Kafka 중복은 발생할 수 있어 DB의 `(campaign_id, user_id)` 고유 제약에 의존한다. Redis failover 직후의 202 영속성, Redis 자체 삭제, DLQ의 내구성은 이 변경으로 해결되지 않는다.

## 배치·DLQ·관측

- [BatchScheduler](app/campaign-core/src/main/java/io/eventdriven/campaign/batch/BatchScheduler.java)의 정기 스케줄은 일별 통계 집계와 주별 배치 메타데이터 정리다. 정합성 복구·DLQ 재처리 Job과 API도 존재하지만, 각각을 정상 처리 경로에서 어떤 역할로 운영할지는 앞으로 명확히 정할 설계 과제다.
- [Consumer](app/campaign-core/src/main/java/io/eventdriven/campaign/application/consumer/ParticipationEventConsumer.java)는 파싱 실패와 DB 실패에 대한 DLQ 경로를 갖는다. 실패 종류별 재시도·ack·재처리 의미는 별도 검증 대상이며, DLQ 적재만으로 유실 방지가 증명되지는 않는다.
- `mcp-server/`는 지표 조회 도구와 폴링·Slack 알림을 포함한다. 하네스 리포트와 알림을 대체·검증하기 전에는 MCP 엔드포인트만 보고 전체 서버를 제거하지 않는다.

## 인프라와 검증 문서

- [Terraform](infra/)은 앱 ASG, ALB, Valkey/Redis, RDS, Kafka 브로커 EC2 등을 정의한다. 리소스 정의와 Kafka 설정·데이터를 포함한 클러스터 완전 재생성 능력은 별개다.
- 앱 EC2의 API·Bridge·Consumer는 현재 같은 애플리케이션에서 실행된다. 역할 분리는 아직 구현 완료가 아닌 성능 개선 가설이다.
- 환경 기동·종료는 [운영 가이드](ops/README.md), 부하 테스트 모드는 [테스트 가이드](stress-test/TEST_GUIDE.md), 하네스 도입 순서는 [계획](docs/plans/performance-harness.md)을 따른다.
- 날짜별 수치와 장애 원인은 [문서 안내](docs/README.md)에서 해당 보고서를 찾아 확인한다. 오래된 문서의 "현재"는 작성 당시를 가리킨다.
