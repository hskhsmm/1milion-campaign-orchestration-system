# 150만 건 정합성 재검증과 후속 작업

2026년 11월 15일 서울 테스트 환경에서 공용 앱 3대로 150만 건을 두 번 완주했다. 두 회차 모두 HTTP 202, Kafka 발행, MySQL 성공이 각각 150만 건으로 일치했다. 클라이언트 p95는 약 1.12초로 두 회차 모두 k6 기준인 1초를 넘었다.

## 검증한 변경

| PR | 변경 | 이번 시험에서 확인한 범위 |
| --- | --- | --- |
| [#124](https://github.com/hskhsmm/1milion-campaign-orchestration-system/pull/124) | 공용·API 전용·워커 전용 실행 지원 | 공용 앱 3대 고정 구성에서 검증 |
| [#126](https://github.com/hskhsmm/1milion-campaign-orchestration-system/pull/126)·[#127](https://github.com/hskhsmm/1milion-campaign-orchestration-system/pull/127) | 가상 스레드와 API·워커 분리 비교, Consumer 장애 조사 | 2차 비교에서 앞선 공용 3대를 기준으로 선정 |
| [#128](https://github.com/hskhsmm/1milion-campaign-orchestration-system/pull/128) | JDBC 배치 안에서 동일한 캠페인·사용자 중복을 제거해 MySQL 1869로 인한 반복 재시도 방지 | 반영된 정식 이미지의 정상 부하 경로 검증. 이 회차에서 강제 종료는 주입하지 않음 |
| [#129](https://github.com/hskhsmm/1milion-campaign-orchestration-system/pull/129) | 운영 가상 스레드 비활성화, Kafka 보관 스크립트, EBS 선언값 반영 | 운영 프로필의 가상 스레드 끔 설정으로 실행. Kafka 보관 스크립트 및 Terraform 적용은 시험 범위 밖 |

## 시험 조건

- Git SHA `6733bb66376fd486f66c3305bbc4df36bdc87fc4`. CodeDeploy `d-TNM17RNXL`의 이미지로 앱 3대를 기동했다.
- `combined-fixed`로 앱 ASG min/desired/max를 모두 3에 맞췄다. 전용 워커는 0대다. 가상 스레드는 끄고 Bridge claim은 `N=10`, 묶음 ACK를 사용했다.
- 서울의 부하 발생기에서 k6 1.7.1, `shared-iterations`, VU 3,000, 합성 사용자 150만 건을 사용했다.
- 600과 603은 각 본시험 전에 별도 캠페인으로 합계 1만 건을 예열했다. 세 본시험 캠페인은 서로 다른 Redis primary를 사용했고 이전 캠페인 키를 보존했다.
- Redis의 실제 `maxmemory`는 primary당 402,653,184 bytes(384 MiB)다. 첫 시험은 192,000,000 bytes에서 자동 중단하도록 설정했다. 이후 두 시험은 메모리 여유를 확보하기 위해 342,000,000 bytes를 중단 기준으로 사용했다.

## 시험 결과

| 지표 | 598 · 중단 | 600 · 완주 | 603 · 완주 |
| --- | ---: | ---: | ---: |
| HTTP 202 집계 | 1,444,222 | 1,500,000 | 1,500,000 |
| Kafka 발행 / MySQL SUCCESS | 각 1,447,153 | 각 1,500,000 | 각 1,500,000 |
| 평균 HTTP 202 처리율 | 4,393건/s | 5,034건/s | 4,991건/s |
| 클라이언트 p95 / p99 | 1.94 / 3.69초 | 1.128 / 1.456초 | 1.121 / 1.385초 |
| Redis Queue / in-flight 최대 표본 | 53,359 / 210 | 12,990 / 172 | 5,439 / 228 |
| Kafka lag 합계 최대 표본 | 654 | 893 | 688 |
| 대상 Redis primary 사용량 최대 / 드레인 뒤 | 194.5 / 191.0MB | 200.9 / 197.0MB | 201.6 / 198.2MB |
| 앱 프로세스 CPU 최고 표본 | 98.6% | 87.4% | 73.4% |
| HikariCP pending 최대 | 0 | 0 | 0 |
| 시험 중 관측 5xx / 캠페인 DLQ | 0 / 0 | 0 / 0 | 0 / 0 |
| 종료 후 Queue / in-flight / lag | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 0 / 0 |

598은 Redis 사용량이 194.5MB인 표본에서 192MB 기준에 따라 k6를 중단했다. 당시 Redis가 요청을 거절하거나 eviction이 발생한 증거는 없었다. k6에 집계된 202와 Redis 접수 사이 차이는 2,931건이다. 중단 시 진행 중이던 요청과 관련됐을 수 있으나 요청별 응답은 확인할 수 없다. Redis 재고 차감량, Kafka 발행, MySQL 성공은 1,447,153건으로 일치했다.

600과 603은 사용자 및 순번 고유 개수가 모두 150만 건이고 순번 범위는 1~1,500,000이었다. 두 시험 모두 150만 건 총량과 정합성을 확인했다. k6 종료 코드 99의 원인은 p95 < 1,000ms 기준 미달이다. 정합성 dry-run은 600과 603에 각각 `CLOSED_REDIS_RESIDUE` 경고 1건을 보고했다. 두 캠페인 모두 DB에서 CLOSED이고 Queue와 활성 상태는 비워졌으며, Redis 재고·총재고 키가 남아 있었다. dry-run은 데이터를 변경하지 않았다.

## 지표 해석

- **Bridge와 Consumer는 이번 부하를 따라갔다.** 캠페인 603에서 API·Bridge·Consumer 처리량은 안정 구간에 초당 약 5천 건으로 비슷했다. 부하 중 적체는 변동했고 종료 때 0으로 돌아왔다. 150만 건 기준에서 claim을 늘려야 한다는 근거는 관측되지 않았다.
- **API 지연은 별도 문제다.** Grafana에서 앱 내부 p99는 안정 구간에 약 120~135ms였고 k6 전체 실행 p99는 약 1.385초였다. 계측 시작점과 집계 범위가 다르다. 같은 요청 경로의 클라이언트·ALB·앱 타이밍을 대조하기 전에는 차이가 생긴 위치를 특정할 수 없다. Hikari 대기는 0이었다.
- **드레인 뒤에도 Redis 메모리가 남았다.** 두 완주 모두 대상 primary가 최대 약 201MB를 사용했다. 큐가 빈 뒤에도 197~198MB가 남아 연속 실행에서는 시작 메모리와 참여 중복 방지 키의 보존 기간이 중요하다. 200만 건은 이번 시험에 포함되지 않았다.
- 첫 회차와 완주 회차에서 예열, Redis primary, 중단 기준이 달랐다. 완주 두 회차의 p95는 약 7ms, 평균 접수율은 약 0.9% 차이였다. 첫 회차와의 차이를 예열 또는 특정 변경의 효과로 확정할 수 없다.

## 남은 작업

1. [#125](https://github.com/hskhsmm/1milion-campaign-orchestration-system/pull/125)는 이번 이미지에 포함되지 않았다. 마지막 재고에서 Lua의 응답만 유실되면 재시도에서 이미 접수된 사용자가 재고 소진 응답을 받을 수 있다. Rate Limit 실패 경로의 무조건 해제도 다른 요청의 키를 지울 수 있다. [변경 요청 리뷰](https://github.com/hskhsmm/1milion-campaign-orchestration-system/pull/125#pullrequestreview-5909990139)에 두 케이스의 검증을 남겼다.
2. p95 1초 목표를 유지한다면 클라이언트와 ALB·서버 지연을 구간별로 대조한다. 시험은 150만 건 총량 검증이며 5,000 RPS 장기 운영의 보장을 의미하지 않는다.
3. 200만 건은 primary별 시작 메모리와 잔존 키 보존 정책을 먼저 확인한 뒤 검증한다.

## 증거와 종료 전 확인

| 캠페인 | 실행 ID |
| --- | --- |
| 598 | `20261115T080738Z-integrity-150m` |
| 600 | `20261115T084708Z-integrity-150m` |
| 603 | `20261115T092335Z-integrity-150m` |

k6 요약, Redis 직접 표본, Prometheus 조회·쿼리, MySQL 직접 조회, 정합성 dry-run을 실행별로 보관했다. 캠페인 603의 Grafana 캡처 15장은 18:23:23~18:30:00 KST 구간이다. 원본과 캡처는 저장소 밖에 보관한다.

종료 전 재조회에서 598~603의 Queue와 in-flight가 모두 0이었고 Kafka 그룹 lag도 0이었다. 598은 중단된 시험이라 재고 52,847건과 활성 상태가 남아 있었다. 테스트 환경 종료로 Redis가 제거되면 그 Redis 상태도 사라진다. 이미 반영된 참여 이력은 MySQL에 기록돼 있다.
