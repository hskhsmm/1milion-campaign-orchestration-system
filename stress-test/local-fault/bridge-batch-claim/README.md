# Bridge batch claim 로컬 장애 시험

[로컬 검증 보고서](../../../docs/reports/2026-11-03-bridge-batch-claim-local.md) 5·6장의 재현 도구다. **로컬 Docker 전용**이며 AWS에 접속하지 않는다. 기존 `app/campaign-core/docker-compose.yml`의 컨테이너·볼륨과 이름·포트를 분리했다(앱 `127.0.0.1:18080`, MySQL `127.0.0.1:13306`). compose의 비밀번호는 이 시험 전용 임의값이다.

| 파일 | 용도 |
| --- | --- |
| `compose-kill.yml` | 앱 강제 종료 시험. Redis Cluster 3 primary(replica 없음). 앱 이미지 `bbc-kill-app:local`을 작업 트리에서 빌드 |
| `compose-split.yml` | `compose-kill.yml`에 덧붙여 API(18080)와 워커(18081)를 같은 이미지로 분리 |
| `compose-failover.yml` | failover 시험. Redis Cluster 3 primary + 3 replica. 위 이미지를 재사용 |
| `lib.sh` | helper: 살아 있는 노드로 redis-cli, 캠페인 슬롯의 primary 찾기, 캠페인 생성, 병렬 요청, Kafka offset 합 |
| `obs.sh <campaignId> [label]` | 한 줄 관측: Queue LLEN, in-flight ZCARD, DB(행·고유 사용자·순번·고유 순번·SUCCESS), DLQ, lag, 앱 claim/발행/ACK/reclaim 지표 |

## 공통

```sh
cd stress-test/local-fault/bridge-batch-claim
export COMPOSE_FILE=compose-kill.yml        # 또는 compose-failover.yml
docker compose up -d --build                # failover는 --build 없이 --pull never (로컬 이미지만 사용)
source lib.sh
id=$(create_campaign test 1000)
./obs.sh $id baseline
docker compose down -v                      # 끝나면 이 프로젝트의 컨테이너·볼륨만 삭제
```

`BRIDGE_CLAIM_BATCH_SIZE`는 compose의 app 환경변수(기본 50)로 바꾼다. 앱 지표는 앱 재시작 시 초기화되므로 재시작 전후 값을 합산하지 않는다. Kafka가 중지된 동안 `obs.sh`의 lag는 조회 실패로 0이 나오므로 근거로 쓰지 않는다.

## 앱 강제 종료 (보고서 5장)

```sh
offsets_before=$(topic_offsets)
docker compose stop kafka
send $id 1001 1100 1                         # HTTP 코드 분포 확인 (202:100)
./obs.sh $id "before kill"                   # in-flight ≥ 1 확인
docker kill --signal KILL bbc-kill-app-1
docker compose start kafka
docker compose start app
# 실제 lease 5분 경과 후 reclaim. 10초마다 obs.sh로 Queue/in-flight 0, DB 100 확인
./obs.sh $id final; echo "$offsets_before → $(topic_offsets)"
```

## API·워커 분리 후 종료 시험

앱과 워커에서 같은 이미지가 실행되는지 확인한다. 참여 요청은 API에만 보내고 워커의 18081 포트는 헬스·메트릭 조회에 사용한다. 각 시험은 새 캠페인을 만들고 앞 시험의 드레인이 끝난 뒤 시작한다.

```sh
cd stress-test/local-fault/bridge-batch-claim
export COMPOSE_FILE=compose-kill.yml:compose-split.yml
export COMPOSE_PROJECT_NAME=bbc-role
docker compose build app
docker compose up -d --no-build
source lib.sh
curl -fsS http://127.0.0.1:18080/actuator/health
curl -fsS http://127.0.0.1:18081/actuator/health
docker inspect -f '{{.Image}}' "$(docker compose ps -q app)" "$(docker compose ps -q worker)"
```

1. 정상 경로가 끝난 뒤 `docker compose stop worker`로 SIGTERM을 보낸다. 종료 코드는 `docker inspect -f '{{.State.ExitCode}}' "$(docker compose ps -aq worker)"`에서 확인한다. 137이면 시간 내 종료에 실패한 것이다.
2. 워커가 내려간 동안 새 캠페인에 참여한다. API의 `202` 수만큼 Queue에 쌓이고 in-flight와 DB는 0이어야 한다. `docker compose start worker` 뒤 DB 반영과 드레인을 확인한다.
3. 강제 종료는 새 캠페인에서 Kafka offset을 기록한 뒤 Kafka를 잠깐 멈춘다. API로 접수한 이벤트가 in-flight에 남은 것을 확인한 뒤 `docker compose kill -s SIGKILL worker`를 실행한다. 종료 코드 137과 in-flight 보존을 확인한다. 워커가 없는 동안 같은 캠페인에 추가 요청을 넣어 Queue에 보관되는지도 본다.
4. Kafka를 먼저 시작하고 워커를 기동한다. 추가로 접수한 Queue 이벤트가 먼저 반영되고, 기존 in-flight는 5분 lease 만료 후 워커가 다시 가져간다. `docker compose logs worker`에서 reclaim 로그를 확인한다.
5. `OBS_APP_URL=http://127.0.0.1:18081 bash ./obs.sh <campaignId> final`과 Kafka offset 차이를 함께 기록한다. 모든 사용자·순번이 DB에 한 번씩 반영됐는지, Queue·in-flight·lag와 DLQ가 모두 0인지 확인한다. Kafka는 재발행 중복이 가능하므로 offset 차이는 DB의 최종 유일 건수와 별도로 기록한다.

시험을 마치면 `docker compose down -v`로 이 로컬 시험 프로젝트만 정리한다.

## failover (보고서 6장)

`export COMPOSE_FILE=compose-failover.yml`. 각 시나리오는 새 캠페인을 쓰고, 끝나면 죽인 노드를 다시 시작해 3 primary + 3 replica로 되돌린 뒤 다음 시나리오로 간다. 보고서 6-4의 순서는 다중 샤드 → B → A → C다.

공통 명령:

```sh
source lib.sh
probe() { curl -s -o /dev/null -w '%{http_code}/%{time_total}s' -m 20 -X POST \
  http://127.0.0.1:18080/api/campaigns/$1/participation -H 'Content-Type: application/json' -d "{\"userId\":$2}"; }
wait_promoted() { for i in $(seq 1 30); do NP=$(primary_of $id); [ -n "$NP" ] && [ "$NP" != "$P" ] && return; sleep 1; done; }
rejoin() { docker start bbc-fo-redis-$P-1; until topology | grep -q "redis-$P slave" && rc CLUSTER INFO | grep -q cluster_state:ok; do sleep 2; done; }
final() {   # 최종 대조: Redis·DB·Kafka
  ./obs.sh $id final
  echo "stock=$(rc GET "stock:campaign:{$id}") offsets=$(topic_offsets)"
}
```

다중 샤드: 캠페인 1~4를 만들고(`create_campaign fo-$i 1000`), 서로 다른 primary인 1·3·4에 `send <id> 1 50 10`을 동시에 보낸 뒤 각 `./obs.sh`. 슬롯 일치는 `rc CLUSTER KEYSLOT "queue:campaign:{<id>}"`와 `"bridge:inflight:campaign:{<id>}"`.

### B. batch claim 직후 primary 장애 (Kafka 중지)

```sh
id=$(create_campaign fo-B 1000); P=$(primary_of $id); before=$(topic_offsets)
docker compose stop kafka
send $id 2001 2100 10                                  # 202:100
# 해당 primary에서 in-flight가 생긴 직후 kill (보고서: in-flight 10·Queue 90 / 적용 전 30·70)
docker compose exec -T redis-$P redis-cli -p 700$P ZCARD "bridge:inflight:campaign:{$id}"
docker kill --signal KILL bbc-fo-redis-$P-1; wait_promoted
for k in $(seq 1 10); do echo "same=$(probe 1 99910$k) other=$(probe 3 99910$k)"; sleep 2; done  # 캠페인 1은 같은 슬롯 primary, 3은 다른 primary
./obs.sh $id "after failover"                          # Queue·in-flight 보존 확인
docker compose start kafka                             # 이후 5초마다 obs.sh, DB 100·Queue/in-flight 0
final; echo "offset 증가 $(( $(topic_offsets) - before ))"
rc EXISTS 'participated:campaign:{1}:user:<500 받은 userId>'   # 0이면 미접수
rejoin
```

### A. claim 전 primary 장애 (Kafka 정상)

Bridge는 `active:campaigns`에 있는 캠페인만 꺼낸다. 시험용으로 캠페인을 이 Set에서 잠시 빼 Queue에 쌓아 둔다(운영 절차가 아니다).

```sh
id=$(create_campaign fo-A 1000); P=$(primary_of $id); before=$(topic_offsets)
rc SREM active:campaigns $id                           # 시험용 조작
send $id 3001 3100 10                                  # 202:100
sleep 3; rc SISMEMBER active:campaigns $id             # 0
./obs.sh $id queued                                    # Queue 100, in-flight 0
docker kill --signal KILL bbc-fo-redis-$P-1; wait_promoted
sleep 5; ./obs.sh $id "after failover"                 # Queue 100 보존
rc SADD active:campaigns $id                           # 되돌림
# 5초마다 obs.sh, DB 100·Queue/in-flight 0
final; echo "offset 증가 $(( $(topic_offsets) - before ))"   # 100이면 중복 0
rejoin
```

### C. 처리 중 primary 장애 (Kafka 정상)

```sh
id=$(create_campaign fo-C 5000); P=$(primary_of $id); before=$(topic_offsets)
( send $id 4001 6000 5 > c-http.txt ) & SP=$!         # 2,000건, 병렬 5
sleep 3; ./obs.sh $id "under load"                     # 부하 중 kill
docker kill --signal KILL bbc-fo-redis-$P-1; wait_promoted
wait $SP; cat c-http.txt                               # 예: 202:1969 500:31
# failover 순간 ACK 실패분은 in-flight에 남아 실제 lease 5분 뒤 reclaim된다.
# in-flight 0이 될 때까지 5초마다 obs.sh (최대 약 6분)
final
echo "offset 증가 $(( $(topic_offsets) - before ))"   # = 202 수 + reclaim 재발행 수
# 대조: DB 행 = 202 수, Redis 재고 = 5000 - 202 수, Queue·in-flight·lag 0
rejoin
```

판정은 정합성(202 = DB, 고유 사용자·순번, 재고, 최종 잔량 0), 자동 복구(앱 재시작 없음), 무중단 가용성(장애 중 `500`·타임아웃 수)을 나눠 기록한다. 앱을 재시작해 복구했다면 그 시점을 운영자 조치로 기록한다.
