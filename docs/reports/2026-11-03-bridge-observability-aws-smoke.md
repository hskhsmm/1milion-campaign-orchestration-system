# Bridge 계측 배포 후 AWS 소량 검증

> 실행: 2026-11-03 KST. 앱 커밋 3e5eacf(PR #117), CodeDeploy d-DKV6HFPPL Succeeded.
> 이미지 digest: sha256:12ca37673fada34e60a8dcca145cca240fe8fe6e8ef43635da1b6bc5906b9608.
> 판정: 계측 배포·소량 정합성 smoke 통과. 150만 성능 개선·드레인 병목 원인 확정은 미검증.

## 조건과 사전 점검

AWS ap-northeast-2, t3.small 앱 2대·ASG desired 2/max 3. 실제 앱 2대 이미지 digest 일치·health UP·ALB healthy, Prometheus up 2를 확인했다. 시험 동안 증설 activity는 없고 최종 desired 2다. 스케일링 정책을 변경하지 않았다.

Redis master 3개는 모두 DBSIZE 0·used_memory 약 7MiB·maxmemory 384MiB, 활성 Set 0이었다. Kafka brokers 3·URP 0·lag 0, Hikari pending 0을 확인했다. 메트릭의 count(kafka_brokers)는 exporter 시리즈 수이지 브로커 수가 아니며 kafka_brokers 값 3을 기준으로 했다.

terraform-mcp 저장소를 소유자 ssm-user로 clean main 89902c3→b2ad271→3e5eacf fast-forward했다. 전역 safe.directory 예외는 추가하지 않았다. 테스트는 기존 run-test.sh prod integrity, shared-iterations를 사용했다. 새 캠페인만 만들었고 기존 데이터는 삭제하지 않았다.

## 결과

| 항목 | 100건 smoke | 1만 건 integrity |
| --- | ---: | ---: |
| 캠페인 | 105 | 106 |
| VU | 10 | 100 |
| k6 iterations / 202 | 100 / 100 | 10,000 / 10,000 |
| 실패 / 5xx | 0 / 0 | 0 / 0 |
| 클라이언트 p95 | 183.46ms | 261.31ms |
| 클라이언트 p99 | 233.04ms | 429.17ms |
| k6 실행 전체 평균 요청률 | 171.24/s | 1,067.48/s |
| DB 전체 / SUCCESS | 100 / 100 | 10,000 / 10,000 |
| DB 사용자·순번 중복 | 0 / 0 | 0 / 0 |
| 순번 범위·고유 건수 | 1~100 / 100 | 1~10,000 / 10,000 |
| 최종 Queue / in-flight / stock | 0 / 0 / 0 | 0 / 0 / 0 |

DB 직접 SELECT로 확인했고 두 캠페인 DLQ 합계는 0이다. Kafka 최종 lag 0·URP 0, Hikari pending 0, 활성 Set 0을 확인했다. 순번의 범위/고유성 검증이며 개별 사용자와 접수 순번의 외부 원본 전체 대조는 수행하지 않았다.

smoke는 14:58:38~14:58:41 KST, 1만 건은 15:00:28~15:00:46 KST(실행 준비와 DB 확인 포함)다. 이 시각 차이를 순수 drain 시간으로 표현하지 않는다. k6 로그의 thresholds=false는 해당 threshold가 실패하지 않았다는 summary 표현이며 프로세스도 정상 종료했다.

## 실제 계측 관측

앱 부팅 뒤 100+1만 건을 합한 누적 sum/count로 계산한 클라이언트 평균이다. 특정 부하 안정 구간 평균이 아니고 비교 기준선의 개선 수치도 아니다.

| 앱 | claim | ACK | Kafka send 동기 | Kafka 성공/실패 응답 |
| --- | ---: | ---: | ---: | ---: |
| i-02b3db28e5336dfd0 | 2.027ms | 0.954ms | 0.232ms | 8.891ms |
| i-07ac6b3ff72e02fb8 | 3.894ms | 1.432ms | 0.226ms | 12.167ms |

claim count는 빈 선점도 포함한다. 응답 시간은 send 대기와 겹치므로 합산하지 않는다. 이번 조건에서는 순차 Redis claim의 호출 대기가 send 동기 호출보다 크게 관측됐다. AZ·연결 경합·Redis 내부 지연 중 어느 원인인지는 확정하지 않았다. 비동기 Kafka 응답이 더 길다는 사실만으로 그것을 순차 루프의 직접 병목으로 단정하지 않는다.

새 조회 목록으로 Prometheus instant·query_range 수집에 성공했다. 부하 종료 1분 뒤 instant rate 평균은 NaN이었고 이를 0으로 처리하지 않았다. 시험 구간의 range 원본과 누적 sum/count를 따로 저장했다. 짧은 실행은 1분 rate 창에 희석되므로 지속 가능 RPS나 drain 처리량의 근거로 쓰지 않는다.

## 발견·복구: Grafana provider 파일 권한

apply-provisioning.sh 실행 뒤 Grafana가 재시작을 반복했다. 로그는 dashboard.yml permission denied였다. mktemp의 0600 권한을 cp로 root 소유 새 provider 파일에 유지해 Grafana UID가 읽지 못한 것이 원인이다.

실환경의 비밀값 없는 provider 설정만 0644로 변경하고 Grafana를 재기동했다. 이후 running/restart 0, /api/health database ok, 새 패널 4개 포함 dashboard JSON 20개를 확인했다. 대시보드 원본은 적용 전 /tmp/bridge117/campaign-before117.json에 보관했다. 로그인 후 실화면 렌더링은 아직 확인하지 않았다.

재발 방지 변경은 별도 fix/grafana-provisioning-permissions 브랜치에서 install -m 0644, 디렉터리 0755를 명시한다. 엄격한 umask 077에서 실제 스크립트를 실행하는 로컬 회귀 시험은 통과했다. sudo/docker는 테스트용 stub이라 실제 컨테이너를 건드리지 않는다.

## 증거와 다음 단계

원본은 로컬 artifacts/bridge117-aws-2026-11-03 아래 remote-evidence.tar.gz·raw/와 SSM 조회 결과로 보관했다(Git에 넣지 않음). 원격 증거는 terraform-mcp /tmp/bridge117에 있다. 개별 요청 원문·자격 증명은 수집하지 않았다.

최대 Queue/메모리 시계열, 정밀 drain 시간, E2E 지연, 계측 전후 오버헤드, 150만 동일 조건 비교는 이번 시험에서 측정하지 않았다. API/Worker 자원 분리나 batch claim을 적용한 것도 아니다. 다음은 고정 자원에서 충분한 길이의 유입/드레인 구간을 수집하고 Redis 명령·연결/AZ·CPU/GC를 대조하는 것이다.

서버는 사용자 요청에 따라 켜 둔 상태다. env-down·스케일링 정책 변경·기존 데이터 삭제는 하지 않았다. 신규 캠페인의 참여 키는 잔류할 수 있으므로 다음 대규모 시험 전 메모리와 대상 데이터를 다시 확인한다.
