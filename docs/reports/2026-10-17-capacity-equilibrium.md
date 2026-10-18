# 지속 RPS 평형점 탐색: 500·750·1,000 RPS 10분 유지

> 실행: 2026-10-17, AWS `ap-northeast-2` 테스트 환경
> 기준 코드·배포 이미지: `89902c3d8c86164fc03afef3f04738b65dab68ec`
> 목적: [10-15 탐색](2026-10-15-capacity-baseline.md)의 다음 실행 계획 2·3번. 낮은 단일 목표 RPS를 길게 유지해 Queue가 쌓이지 않는 구간을 확인한다. **탐색 기록이며 지속 가능 처리량 확정이나 최종 PASS/FAIL 판정이 아니다.**

## 실험 조건

- 앱: `t3.small` 2대 (CPU credit `unlimited`). ASG min 2 / desired 2 / max 3. 세 실행 모두 같은 두 인스턴스(컨테이너 시작 18:18 KST)였고 실행 중 scaling activity는 없었다.
- 부하 발생기: `terraform-mcp` EC2의 k6 1.7.1. 저장소는 `89902c3`이며 k6 스크립트 md5가 운영 PC의 `origin/main`과 같았다.
- 실행 방식: `run-test.sh prod capacity` 단일 목표 모드. `START_RPS=50`, `WARMUP_SECONDS=30`, `STEADY_SECONDS=600`, `COOLDOWN_SECONDS=5`, `MAX_VUS=3000`, `PRE_ALLOCATED_VUS`=목표 RPS. 실행마다 k6 setup이 새 캠페인(재고 9,999,999)을 만들었다.
- 시작 전 기준선: active Set 비어 있음, Kafka lag 0, Hikari pending 0, ALB healthy 2.
- 실행은 500 → 750 → 1,000 순서로 이어서 했다. 앞 실행의 캠페인은 OPEN·active Set에 남아 있었다(Queue 0). 따라서 750·1,000 실행에는 Bridge가 순회할 빈 캠페인이 1~2개 더 있었다.
- 원본 증거는 로컬 `evidence/`의 아래 세 디렉터리에 있다. `evidence/`는 Git에서 제외된다. 각 디렉터리의 `manifest.md`에 조건을, `prometheus/queries.tsv`에 조회 쿼리를 기록했다.

| 목표 | RUN_ID | 캠페인 | 부하 시각 (KST) | 분석한 안정 구간 (KST) |
| --- | --- | ---: | --- | --- |
| 500 RPS | `20261017T094832Z-capacity-500` | 75 | 18:48:37~18:59:12 | 18:50:00~18:59:00 |
| 750 RPS | `20261017T101040Z-capacity-750` | 76 | 19:10:52~19:21:27 | 19:12:00~19:21:00 |
| 1,000 RPS | `20261017T104301Z-capacity-1000` | 77 | 19:43:12~19:53:47 | 19:44:30~19:53:30 |

안정 구간은 워밍업 30초와 `rate(...[1m])` 창이 채워지는 시간을 제외한 9분이다.

## 관측 결과

### 처리율과 지연 (안정 구간 평균)

| 목표 | API 202¹ | Bridge 발행¹ | DB 성공 경로¹ | 서버측 API p95 / p99² | 앱 CPU³ | Hikari pending 최대 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 500 RPS | 500.0건/s | 500.0건/s | 500.0건/s | 6.8 / 14.0 ms | 약 30% / 30% | 0 |
| 750 RPS | 749.2건/s | 748.0건/s | 749.2건/s | 7.8 / 15.3 ms | 약 40% / 50% | 0 |
| 1,000 RPS | 1,000.0건/s | 1,000.3건/s | 1,000.1건/s | 8.0 / 16.8 ms | 약 40% / 50% | 0 |

¹ Prometheus 10초 step의 `sum(rate(...[1m]))` 평균. API는 참여 요청의 `http_server_requests_seconds_count{status="202"}`, Bridge는 `bridge_messages_published_total`, DB는 `consumer_db_committed_total`이다. `consumer_db_committed_total`은 물리 INSERT 수가 아니므로 DB 건수는 아래 직접 조회로 확인했다. 750 RPS의 Bridge 발행률은 한 표본에서 665건/s까지 내려갔다가 회복했다.

² `http_server_requests_seconds_bucket`의 `histogram_quantile` 1분 창 값의 안정 구간 평균. k6 클라이언트 지연이 아니다.

³ 인스턴스별 `system_cpu_usage` 안정 구간 평균(두 대). 호스트 전체 CPU이며 앱 프로세스만의 값이 아니다.

### Queue와 lag

| 목표 | Redis `LLEN` 평균 / 최대⁴ | `LLEN` 기울기⁵ | Kafka lag 최대 / 종료 후⁶ |
| --- | ---: | ---: | ---: |
| 500 RPS | 25 / 196건 | −0.003건/s | 48 / 0 |
| 750 RPS | 41 / 606건 | +0.008건/s | 69 / 0 |
| 1,000 RPS | 44 / 447건 | −0.006건/s | 113 / 0 |

⁴ `redis-watch-start.sh`의 1초 단위 직접 `LLEN` 기록, 안정 구간 기준. 500 RPS 실행만 감시가 부하 시작 25초 뒤(워밍업 중)에 시작됐다. 안정 구간은 모두 포함된다.

⁵ 안정 구간 `LLEN` 표본의 선형회귀 기울기. 세 실행 모두 0에 가까워 Queue가 증가하지 않았다. 부하 종료 직후 1~2초 안에 `LLEN=0`이 됐다.

⁶ `sum(kafka_consumergroup_lag{consumergroup="campaign-participation-group"})`, 10초 step 구간 조회의 관측 최대값과 수집 시점 값.

### k6와 최종 건수

| 목표 | 202 건수 | 5xx / 실패 | `dropped_iterations` | k6 p95 (전체 실행) | DB SUCCESS 직접 조회 | sequence·user 중복 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 500 RPS | 309,499 | 0 / 0 | 0 | 11.5 ms | 309,499 | 0 / 0 |
| 750 RPS | 463,874 | 0 / 0 | 0 | 12.9 ms | 463,874 | 0 / 0 |
| 1,000 RPS | 618,249 | 0 / 0 | 0 | 13.0 ms | 618,249 | 0 / 0 |

- 각 캠페인의 status API `successCount`와 Redis 재고 감소량도 202 건수와 같았다. `failCount`는 모두 0이었다.
- k6 최대 지연은 2.3~4.8초였다. `K6_SUMMARY_EXPORT` 결과에는 p99가 없어서 k6 클라이언트 p99는 기록하지 못했다.
- DB 직접 조회는 앱 인스턴스에서 `mysql:8.4` 컨테이너로 SELECT만 실행했다(`db-counts.txt`).
- 앱 로그에서 테스트 요청과 관련된 ERROR는 없었다. 기록된 ERROR는 모두 외부의 `GET /` 요청(`NoResourceFoundException`)이었다.

## 현재 결론과 판정 범위

현재 2대 구성에서 **500·750·1,000 RPS를 각각 10분 유지하는 동안 Queue가 쌓이지 않았다.** 각 단계에서 API 접수, Bridge 발행, DB 성공 경로 처리율이 같았고, 부하 종료 후 Queue·lag가 바로 0이 됐다. DB 건수와 중복도 접수 수와 맞았다.

다만 다음 이유로 **1,000 RPS를 지속 가능 처리량으로 확정하지 않는다.**

- 운영 목표(지연·오류·유지 시간)가 아직 합의되지 않았다(10-15 계획 1번).
- 단계마다 1회씩만 실행해서 반복 변동폭을 확인하지 않았다.
- 접수→DB 반영 지연은 계측이 없어 측정하지 않았다.
- 10분 유지는 장시간 운영을 대신하지 않는다. `t3.small`은 `unlimited` 모드여서 이번 실행에서 CPU 제한은 없었지만, 장시간 높은 CPU는 추가 크레딧 비용이 생긴다.

10-15 1,000 RPS 60초 점검에서는 Prometheus Queue Gauge가 최대 11,711건이었다. 이번 1,000 RPS의 Gauge 최대는 149건, 직접 `LLEN` 최대는 447건이었다. 두 실행은 VU 설정, 실행 길이, 인스턴스 기동 후 경과 시간이 달라서 이 차이의 원인은 판단하지 않는다.

10-15 탐색에서는 2,000 RPS 단계부터 Queue가 쌓였다. 따라서 Queue가 쌓이기 시작하는 지점은 **1,000~2,000 RPS 사이**로 좁혀졌다.

## 다음 실행 계획

1. 같은 조건으로 1,250·1,500 RPS를 10분씩 유지해 Queue가 쌓이기 시작하는 지점을 찾는다.
2. 그 지점 아래 단계를 2회 이상 반복해 변동폭을 확인한다.
3. 실행 사이에 앞 캠페인을 정리할지(active Set 순회 대상) 정하고, 정리하지 않으면 manifest에 남긴다.
4. 접수→DB 반영 지연 계측은 [성능·관측 하네스 계획](../plans/performance-harness.md)의 E2E 계측 단계에서 다룬다.

이 결과는 [성능·관측 하네스 계획](../plans/performance-harness.md)의 기준선 탐색 기록이다. 판정 기준은 [부하 테스트 가이드](../../stress-test/TEST_GUIDE.md) 8장의 탐색/최종 판정 구분을 따른다.
