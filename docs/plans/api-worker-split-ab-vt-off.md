# API·워커 분리 A/B 2차 계획 (가상 스레드 끔, 2026-11-10)

1차 A/B는 A·B 모두 가상 스레드 켬(캐리어 기본 2개)에서 측정했다. 이후 [원인 조사](../reports/2026-11-09-redis-timeout-cause.md)에서 이 설정이 회차마다 서버 1대를 느리게 만들어 p99를 흔든다는 것이 확인됐고, t3.small에서는 **가상 스레드 끔**을 기준 설정으로 정했다. 2차는 같은 비교를 끔으로 다시 한다. 1차 결과와 판정은 [1차 보고서](../reports/2026-11-09-api-worker-split-ab.md), 1차 계획은 [1차 계획](api-worker-split-ab.md)에 있다.

## 바꾸는 것과 고정하는 것

| 항목 | 2차 값 | 1차 대비 |
| --- | --- | --- |
| 가상 스레드 | **끔** (`SPRING_THREADS_VIRTUAL_ENABLED=false`, 앱 서버 전부) | 변경 (유일한 변수) |
| Tomcat 요청 스레드 | 기본 200개/대 (끔이라 적용됨). A 600개, B API 400개 | 1차는 상한 없음 |
| 코드·이미지 | `a6f8a02`, digest `32fc3cfc6b03`, 배포 `d-8T3HY8HSL` | 같음 |
| A | `combined-fixed`, `t3.small` 공용 3대 | 같음 |
| B | `split-fixed`, API 2대 + 워커 1대 | 같음 |
| 공통 | `N=10`, 묶음 ACK, Bridge 회차 한도 500, Kafka 파티션 10개, Redis 3샤드(primary당 384MB), 정기 배치 꺼짐 | 같음. Bridge 한도는 이번에도 바꾸지 않는다 |
| 캠페인 | 슬롯 5462-10922 샤드 고정, 실행 후 정리 | 같음 |
| 기록 도구 | JFR(default + 모니터·park 5ms)·GC·safepoint 로그·스레드 샘플러(0.2초)·스케줄러·Tomcat 표본(0.5초)을 **A·B 모두 같게** 붙인다 | 1차는 없음. 측정 시작 전(11-10)에 변경: 끔에서도 켬 조사와 같은 방식으로 원인을 볼 수 있게 하려는 것. A·B 조건은 같으므로 2차 안의 비교는 유효하고, 1차 값과의 직접 비교는 하지 않는다 |

가상 스레드 끔은 코드·배포 설정에 아직 반영되지 않았다. 시험에서는 원인 조사 때와 같이 **앱 서버의 `/opt/campaign-core/docker-compose.prod.yml`에 환경 변수 한 줄을 넣고 컨테이너를 다시 만든다.** 서버가 교체되면 기본값(켬)으로 돌아가므로 회차마다 확인한다.

## 절차

1. **환경:** `APP_LAYOUT=combined-fixed APP_ROLE_DEPLOYMENT_ID=d-8T3HY8HSL make -C ops env-up` → 3대 `CAMPAIGN_ROLE=combined`, 이미지 digest, ALB healthy 3 확인.
2. **끔 적용 (각 앱 서버):**
   ```bash
   F=/opt/campaign-core/docker-compose.prod.yml; C=campaign-core-app
   sed -i '/SPRING_THREADS_VIRTUAL_ENABLED/d' $F
   sed -i '/SPRING_PROFILES_ACTIVE: prod/a\      SPRING_THREADS_VIRTUAL_ENABLED: "false"' $F
   ECR_IMAGE=$(docker inspect -f '{{.Config.Image}}' $C) docker-compose -p campaign-core -f $F up -d --force-recreate app
   docker exec $C sh -c 'tr "\0" "\n" </proc/1/environ | grep SPRING_THREADS_VIRTUAL_ENABLED'   # false 확인
   ```
   재생성 중 헬스 체크 실패로 교체되지 않게 앱 ASG `ReplaceUnhealthy`를 잠시 정지하고, ALB healthy를 확인한 뒤 재개한다. 이 서버에는 `docker compose`가 아니라 `docker-compose`만 있다.
3. **예열:** 1만 건 200 VU 1회. 비교에 넣지 않는다.
4. **A 측정:** 3,000 RPS 300초 3회 → 30만 건 3회. 각 실행 전 Queue·in-flight·lag 0, 활성 캠페인 0, k6 0개를 확인한다.
5. **A 결과로 합격선 확정:** B를 열기 전에 아래 기준의 A 값(중앙값·최저값)을 이 문서에 적는다.
6. **B 전환:** `make -C ops app-down` → `APP_LAYOUT=split-fixed APP_ROLE_DEPLOYMENT_ID=d-8T3HY8HSL make -C ops app-up` → 역할(API 2·워커 1), 워커 파티션 10개 할당 확인 → **2번(끔 적용)을 3대 모두에** → 예열 1만 건.
7. **B 측정:** A와 같은 횟수.
8. **(시간이 되면) A 확인 1회씩:** 시간대·Redis 상태 차이를 보기 위해 A로 되돌려 3,000 RPS 1회와 30만 건 1회.
9. 결과 보고서 작성 후 env-down(사용자 지시 시).

예상 소요: env-up 약 20분, 구성당 약 50분(예열·측정 6회), 전환 약 20분. 8번까지 약 2시간 40분.

## 채택 기준 (사전 확정)

판정 지표를 1차의 서버 p99에서 **k6 p99**로 바꾼다. 끔에서는 Tomcat 스레드를 기다리는 시간이 서버 지표에서 빠져 서버 p99가 실제보다 낮게 나오기 때문이다([원인 보고서](../reports/2026-11-09-redis-timeout-cause.md) "남은 위험"). 하네스가 11-09부터 k6 p99를 기록하므로 A·B 모두 같은 값으로 비교할 수 있다. 서버 p95·p99도 함께 기록한다.

| 항목 | 기준 |
| --- | --- |
| 유효 회차 | 고정 유입률은 `dropped_iterations=0`이고 202 평균이 목표 ±2% 안. 벗어나면 `INCONCLUSIVE`로 비교에서 제외하고 대체 회차를 돌린다 |
| 느린 응답 | 3,000 RPS **k6 p99** 구성별 중앙값이 A보다 20% 이상 낮고, A 유효 회차 최저값보다도 낮으면 개선. 20% 이상 낮지만 A 최저값보다 높으면 `INCONCLUSIVE` |
| 보통 응답 | 3,000 RPS **k6 p95** 중앙값 증가 5% 이내 |
| 30만 건 DB 반영 | 첫 요청 → 마지막 DB 중앙값이 A 중앙값 + 5% 이내(측정 오차 ±2초) |
| 정합성 | 202 = 발행 = DB, 누락·중복·5xx·timeout·ACK 거부·reclaim·DLQ 0. 위반 확인 시 실패 |
| 적체·자원 | Queue·lag가 안정 구간에서 계속 불어나지 않고 종료 후 시한 안에 0. Redis primary 192MB 이하, eviction 0 |

판정은 구성당 유효 3회를 모은 뒤 중앙값으로 한다. B 결과를 본 뒤 기준을 바꾸지 않는다. 바꿔야 할 사정이 생기면 이유와 함께 기록하고 해당 항목은 `INCONCLUSIVE`로 둔다.

**중단 조건:** 5xx 발생, 서버 동결(ALB unhealthy 지속), Redis primary 192MB 초과. 중단하면 강제 중단 뒤 `active:campaigns` 정리를 확인한다(1차 조사 때 잔류 캠페인이 이후 측정을 오염시켰다).

## 알고 들어가는 차이

- **B의 API 동시 처리 상한이 A보다 작다.** 끔에서는 서버당 Tomcat 스레드 200개라 A는 600, B는 API 2대의 400이다. 3,000 RPS에서 1차 A의 k6 p95가 수십 ms였으므로 400칸으로도 충분할 것으로 본다 [추정]. 부족하면 B의 k6 p99와 dropped로 드러난다.
- **B 워커는 API 요청을 받지 않는다.** 1차 B에서 Bridge가 회차 한도 500 근처였다는 관찰 [추정]은 그대로 두고 측정만 한다. 30만 건 미달이 반복되면 다음 단계에서 Bridge 한도만 바꿔 비교한다.
- k6 "최대"는 시험 끝 `/status` 1건이 들어가므로 참여 지연으로 읽지 않는다.

## 이후

2차에서 B가 기준을 충족하면 분리 구성으로 장애 시험 F1~F8(워커 중단·지연·교체 시 정합성과 회복)을 진행한다. 미달이면 원인(Bridge 한도, API 2대 상한 등)을 한 변수씩 바꿔 비교한다. 어느 쪽이든 가상 스레드 끔은 별도 PR로 코드·배포 설정에 반영한다.
