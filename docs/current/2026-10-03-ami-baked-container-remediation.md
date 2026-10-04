# 2026-10-03 AMI 구버전 컨테이너 문제 후속 조치

> 상태: **코드 작업 완료 (미커밋, 미적용)**. AWS 반영(terraform apply, AMI 재생성, DB 정리)과 실환경 검증은 남아 있다.
> 원인 문서: `2026-09-28-150m-reproduction-set-removal-trace.md` (§6.2 조치 목록)
> 브랜치: `fix/bridge-queue-orphan-guard` (`main` `bc294b4`에서 분기, 커밋 0개)
> 근거 등급: **[확정]** 실행·원본으로 확인 / **[추정]** 추론 / **[미확정]** 미검증

---

## 0. 착수 시점 상태 확인 (2026-10-03)

9/28 문서 §6.2 조치가 반영됐는지 먼저 확인했다. **하나도 반영되지 않은 상태였다.** [확정]

| 항목 | 확인 결과 | 근거 |
|---|---|---|
| 9/28 이후 커밋 | 없음. `main`, `fix/bridge-queue-orphan-guard`, `test/t06-consumer-redelivery-repro` 모두 `bc294b4` | `git log --all --since=2026-09-27` 빈 결과 |
| Launch Template | v1(04-27), v2(07-01), v3(09-19) 모두 `ami-01c64e7a84a57e681` | `aws ec2 describe-launch-template-versions` |
| 계정 소유 AMI | `ami-01c64e7a84a57e681`(`batch-kafka-app-ami`, 2026-04-27T07:28:16Z) 1개뿐 | `aws ec2 describe-images --owners self` |
| `infra/app-user-data.sh` | Ansible 설치만 있음, 컨테이너 정리 없음 | 파일 |
| Bridge `LLEN` 가드 | 다른 브랜치 작업 트리에 미커밋 상태로만 존재 | `git diff` |
| 10/01 문서 | `2026-10-01-t06-consumer-redelivery-verification.md:291`에 "구버전 컨테이너 자동 기동"을 미해결 위험으로 언급 | 문서 |

---

## 1. 조치 내역

| 9/28 우선순위 | 조치 | 상태 | 파일 |
|---|---|---|---|
| P0 (즉시 완화) | 부팅 시 AMI 잔존 컨테이너 자동 기동 차단 + 삭제 | 코드 완료, **apply 대기** | `infra/app-boothook.sh`(신규), `infra/app-user-data.sh`, `infra/asg.tf` |
| P0 | 잔존 컨테이너 없는 AMI 재생성 | 스크립트 완료, **실행 대기** | `ops/scripts/rebuild-app-ami.sh`(신규), `infra/variables.tf`(`app_ami_id`) |
| P1 | Bridge 해시태그 없는 legacy 큐 키 드레인 제거 | 완료, 테스트 통과 | `ParticipationBridge.java`, `ParticipationBridgeDeactivationTest.java` |
| P1 | 과거 테스트 캠페인 DB CLOSED 정리 | 스크립트 완료, **실행 대기** | `ops/scripts/close-stale-campaigns.sh`(신규) |
| P2 | Bridge `LLEN` 재확인 가드 | 완료(9/28 작업 유지), 테스트 통과 | `ParticipationBridge.java` |
| P2 | QueueMetricsScheduler: 비활성 캠페인 강제 0 제거 → 실제 LLEN 보고 | 완료, 테스트 통과 | `QueueMetricsScheduler.java`, `QueueMetricsSchedulerTest.java` |

### 1.1 P0 즉시 완화: boothook + user-data 정리

**문제의 시간 순서** (9/28 §3.3, 3번째 인스턴스 `i-08786efc…`): 부팅 08:48:54Z → dockerd가 구버전 컨테이너 자동 기동 08:49:10Z (부팅 후 16초). 일반 user-data(셸 스크립트)는 cloud-init 마지막 단계(cloud-final)에서 실행되므로 이 시점보다 늦을 수 있다. user-data에서 `docker rm -f`만 하면 경쟁 조건이 남는다. [추정]

**구성**: Launch Template user-data를 MIME multipart 두 파트로 바꿨다.

| 순서 | 파트 | 실행 시점 | 하는 일 |
|---|---|---|---|
| 1 | `text/cloud-boothook` (`app-boothook.sh`) | cloud-init init 단계. docker.service는 `After=network-online.target`이고 cloud-init.service는 그보다 앞서므로 **dockerd 기동 전** [추정: AL2023 systemd 순서 기준, 실인스턴스 미검증] | `/var/lib/docker/containers/*/hostconfig.json`의 `RestartPolicy.Name`을 `"no"`로 변경 → dockerd가 떠도 자동 기동 안 함 |
| 2 | `text/x-shellscript` (`app-user-data.sh`) | cloud-final | `systemctl start docker` → 잔존 컨테이너 목록 로그 → `docker rm -f` 전부 → `docker image prune -af` → 기존 Ansible 설치 |

- boothook은 **매 부팅마다** 실행된다. 배포된 현재 앱 컨테이너가 재부팅 뒤에도 살아나야 하므로 `/var/lib/batch-kafka-app/boothook-sanitized-<instance-id>` 마커로 인스턴스당 1회만 실행한다. 인스턴스 ID는 `INSTANCE_ID` 환경변수, 없으면 `/var/lib/cloud/data/instance-id`를 쓴다. 마커가 AMI에 구워져도 새 인스턴스는 ID가 달라 반드시 다시 실행된다.
- 예상과 달리 boothook 시점에 docker가 이미 active면 그 자리에서 `docker rm -f`한다(로그에 WARN).
- user-data의 전체 삭제가 안전한 이유: CodeDeploy hook `run-ansible-deploy.sh`는 `cloud-init status --wait`로 cloud-init 완료를 기다린 뒤 배포하므로, 이 시점에는 현재 앱 컨테이너가 아직 없다. [확정: 스크립트 코드]
- 로그: `/var/log/batch-kafka-app-boothook.log`, `/var/log/batch-kafka-app-user-data.log`.

로컬 검증 [확정]:

| 검증 | 결과 |
|---|---|
| `bash -n` (boothook, user-data) | 통과 |
| sed 치환: `{"RestartPolicy":{"Name":"unless-stopped","MaximumRetryCount":0}}` | `{"RestartPolicy":{"Name":"no","MaximumRetryCount":0}}` |
| `terraform validate` | Success |
| `terraform console`로 렌더링한 user-data를 Python `email` 파서로 분해 | `multipart/mixed` → `text/cloud-boothook`(46줄) + `text/x-shellscript`(70줄) |
| docker-in-docker(`docker:29-dind`, dockerd 29): `--restart unless-stopped` 컨테이너 2개 실행 → dockerd 정상 종료 → **sed 없이** 재기동 | 두 컨테이너 모두 `Up 3 seconds` (AMI 부팅 시 자동 기동 재현) |
| 같은 환경: dockerd 종료 → boothook과 같은 sed로 전체 `hostconfig.json` 변경 → 재기동 | 두 컨테이너 모두 `Exited (137)`, `restart=no` → **자동 기동 차단 확인** |

미검증 [추정]: (1) AL2023 실인스턴스에서 boothook이 docker.service보다 먼저 실행되는지, (2) AMI의 docker 버전(29가 아닐 수 있음)에서도 같은지. 실인스턴스 부팅 테스트로만 확정 가능.

### 1.2 P0 AMI 재생성 스크립트

`ops/scripts/rebuild-app-ami.sh` — 기본은 계획만 출력, `CONFIRM=yes`일 때 실행.

1. LT `$Latest` user-data에 `text/cloud-boothook`이 있는지 확인. **없으면 중단**: 임시 인스턴스에서 구버전 컨테이너가 떠 prod Redis에 붙기 때문.
2. 같은 LT로 ASG 밖 임시 인스턴스(`batch-kafka-app-ami-builder`) 1대 기동. CodeDeploy 배포 대상이 아님.
3. SSM Online → `cloud-init status --wait` → boothook/user-data 로그 출력 → 컨테이너·이미지 0개 확인(아니면 실패) → `/opt/campaign-core`, 마커, 로그 삭제 → `cloud-init clean --logs`.
4. 정지 → `create-image` (`batch-kafka-app-ami-YYYYMMDD-HHMM`) → available 대기 → 임시 인스턴스 종료(실패 시에도 `trap`으로 종료).
5. 새 AMI ID와 다음 단계 출력.

`infra/asg.tf`의 AMI는 `var.app_ami_id`(기본값 = 기존 `ami-01c64e7a84a57e681`)로 바꿨다. 새 AMI가 나오면 `terraform.tfvars`에 한 줄만 추가하면 된다.

건식 실행 (2026-10-03, AWS 조회만) [확정]:

```
Launch Template: batch-kafka-app-lt v3
기준 AMI:        ami-01c64e7a84a57e681
임시 인스턴스 서브넷: subnet-03e64a5c78e57a505
중단: LT $Latest user-data에 boothook이 없습니다. infra에서 terraform apply로 LT를 먼저 갱신하세요.
```

→ apply 전이므로 의도대로 중단.

### 1.3 P1 legacy 큐 키 드레인 제거

- `ParticipationBridge`의 `LEGACY_QUEUE_KEY_PREFIX = "queue:campaign:"`와 사이클마다 `RPOP queue:campaign:<id>`를 하던 루프를 삭제했다.
- 근거: 해시태그 전환(`e3bfd7f`, 2026-04-24)은 끝났고, 해시태그 없는 큐 키에 쓰는 코드가 현재 없다(9/28 §7.1). 활성 캠페인당 사이클마다 Redis 명령 1회가 줄어든다.
- 테스트 `drainQueues_doesNotReadLegacyQueueKey` 추가: 구 키 `queue:campaign:62`에 `RPOP`하지 않음을 검증.

### 1.4 P2 QueueMetricsScheduler 고립 위장 제거

변경 전: active Set에 없는 캠페인의 Gauge는 **무조건 0**. 그래서 9/25, 9/28 2차의 고립 큐(206,000건 등)가 대시보드에 0으로 보였다.

변경 후:

| 상황 | 동작 |
|---|---|
| active Set에 있음 | 기존과 동일 (LLEN 보고) |
| Set에 없음 + 마지막 값 > 0 | **실제 LLEN 재조회**해 보고. > 0이면 WARN `active Set에 없는 캠페인 Queue에 잔량 존재 (고립 의심)` |
| Set에 없음 + 마지막 값 0 | 조회 안 함 (LLEN 0 확인 후 종료) |

한계: JVM 재시작 뒤에는 이전에 본 캠페인 목록이 없으므로, 재시작 전에 이미 고립된 큐는 감지하지 못한다. 그 경우는 정합성 검사(`ConsistencyRecoveryService`)로 확인한다. [확정: 코드]

### 1.5 P1 과거 캠페인 정리 스크립트

`ops/scripts/close-stale-campaigns.sh`:

- 앱 ASG 인스턴스 1대에서 SSM으로 `mysql:8.4` 컨테이너를 띄워 실행한다(앱 SG는 RDS 접근 확정). 접속 정보는 배포 playbook이 만든 `/opt/campaign-core/.env.prod`에서 읽는다.
- `.env.prod`는 따옴표 없는 `KEY=VALUE`라 JDBC URL의 `&` 때문에 `source`하지 않고 `grep | cut`으로 값만 읽는다.
- `MAX_ID` 필수. 기본은 `status='OPEN'` 목록 조회만 하고, `CONFIRM=yes`이면 `UPDATE campaign SET status='CLOSED', updated_at=NOW(6) WHERE status='OPEN' AND id <= MAX_ID`를 실행한다. `current_stock`은 기록 보존을 위해 건드리지 않는다.
- Redis 쪽 정리는 필요 없다: `make env-down`이 Redis를 삭제하고, 구버전 컨테이너가 없어지면 재등록 주체도 없다.

로컬 검증 [확정]: 가짜 `.env.prod`(`jdbc:mysql://db.x.rds.amazonaws.com:3306/campaign?useSSL=false&serverTimezone=Asia/Seoul`, 비밀번호 `p@ss=w&rd`)로 원격 명령을 생성·파싱 → `host=db.x.rds.amazonaws.com port=3306 db=campaign user=admin pw=p@ss=w&rd`.

---

## 2. 테스트

작업 트리에서는 미추적 로컬 테스트 파일들 때문에 `gradlew test`가 컴파일에 실패한다(9/28 §7.2와 동일). 그래서 `HEAD` 기준 임시 worktree에 변경 파일 4개만 복사해 실행했다.

### 2.1 변경 후 코드 [확정]

`./gradlew test --offline` → exit 0

| 테스트 클래스 | tests | skipped | failures |
|---|---|---|---|
| `QueueMetricsSchedulerTest` | 5 | 0 | 0 |
| `ParticipationBridgeDeactivationTest` | 5 | 0 | 0 |
| `ParticipationServiceTest` | 8 | 0 | 0 |
| `DlqReplayPolicyServiceTest` | 5 | 0 | 0 |
| `RateLimitServiceTest` | 4 | 0 | 0 |
| `ParticipationEventConsumerTest` | 2 | 0 | 0 |
| `CampaignCoreApplicationTests` | 1 | 1 | 0 |

### 2.2 변경 전 코드(`bc294b4`) + 새 테스트 → 테스트가 기존 결함을 잡는지 [확정]

`10 tests completed, 5 failed`

| 테스트 | 변경 전 |
|---|---|
| flag 없음 + RPOP null + LLEN 0이면 제거 | ok |
| flag 없음 + RPOP null + LLEN 잔량이면 제거 안 함 | **FAIL** |
| 제거 직전 LLEN null이면 제거 안 함 | **FAIL** |
| flag 있으면 큐가 비어도 제거 안 함 | ok |
| 구 큐 키를 읽지 않음 | **FAIL** |
| 활성 캠페인 LLEN을 Gauge에 반영 | ok |
| 큐를 비우고 Set에서 제거되면 Gauge 0 | ok |
| 잔량이 남은 채 Set에서 제거되면 실제 LLEN 보고 (211,595 → 206,000) | **FAIL** (0 보고) |
| 비활성 캠페인은 LLEN 0 확인 후 더 조회 안 함 | **FAIL** |
| 다른 캠페인이 활성이어도 비워진 비활성 캠페인 Gauge 0 | ok |

---

## 3. `terraform plan` 결과 (2026-10-03, `-lock=false`, 읽기 전용) [확정]

```
Plan: 3 to add, 2 to change, 0 to destroy.
```

| 리소스 | 변경 | 이번 작업과 관계 |
|---|---|---|
| `aws_launch_template.app` | update in-place (`user_data` → multipart, `latest_version` 3 → 4) | **이번 작업** |
| `aws_elasticache_replication_group.redis` | create | 무관. env-down으로 삭제된 상태라 생김 (env-up이 담당) |
| `aws_ssm_parameter.redis_cluster_nodes`, `redis_exporter_addr` | create | 무관. Redis와 같이 생김 |
| `aws_instance.terraform_mcp` | update in-place (`user_data` 해시 변경) | 무관. 이전부터 있던 drift |

→ **전체 apply 금지.** LT만 `-target`으로 적용한다.

---

## 4. 남은 작업 (사람이 실행)

| # | 작업 | 명령 | 확인 |
|---|---|---|---|
| 1 | LT에 boothook 적용 | `cd infra && terraform apply -target=aws_launch_template.app` | LT v4 생성, `rebuild-app-ami.sh` 건식 실행이 `boothook 확인: OK` |
| 2 | 즉시 완화 검증 | `make env-up` 후 새 인스턴스에서 `cat /var/log/batch-kafka-app-boothook.log`, `grep -A5 "containers before cleanup" /var/log/batch-kafka-app-user-data.log`, `journalctl -u docker \| grep -i restart` | boothook 로그에 `disable restart policy` 2건, 구버전 컨테이너가 **기동 기록 없이** 삭제됨, env-up 직후 Set에 이전 캠페인이 없음 |
| 3 | 과거 캠페인 정리 | `MAX_ID=<마지막 테스트 id> ./ops/scripts/close-stale-campaigns.sh` → 목록 확인 후 `CONFIRM=yes` | OPEN 목록 비어 있음 |
| 4 | AMI 재생성 | `CONFIRM=yes ./ops/scripts/rebuild-app-ami.sh` | 새 AMI ID 출력 |
| 5 | 새 AMI 적용 | `terraform.tfvars`에 `app_ami_id = "<새 AMI>"` → `terraform apply -target=aws_launch_template.app` | LT v5 |
| 6 | 앱 배포 | 이 브랜치 머지 → CodeDeploy | 로그에 `instance=` 포함된 제거 로그 |
| 7 | 재현 테스트 재실행 | 9/28 2차와 같은 조건(드레인 중 강제 합류) | 고립 없음, DB successCount = 재고 |

- 2번의 boothook 실행 순서(dockerd 기동 전)는 [추정]이다. 실인스턴스에서 `journalctl -u docker`에 구버전 컨테이너 기동 기록이 없어야 확정할 수 있다.
- 6번 전까지 현재 앱 코드는 `bc294b4` 그대로다(legacy 드레인·Gauge 0 위장 유지).

---

## 5. 변경 파일 (미커밋)

| 파일 | 구분 |
|---|---|
| `infra/app-boothook.sh` | 신규 |
| `infra/app-user-data.sh` | 수정 (잔존 컨테이너·이미지 삭제) |
| `infra/asg.tf` | 수정 (multipart user-data, `var.app_ami_id`) |
| `infra/variables.tf` | 수정 (`app_ami_id`) |
| `ops/scripts/rebuild-app-ami.sh` | 신규 |
| `ops/scripts/close-stale-campaigns.sh` | 신규 |
| `ops/scripts/lib-ssm.sh`, `redis-watch-start.sh`, `redis-watch-fetch.sh`, `collect-app-logs.sh`, `capture-boot-containers.sh` | 신규 (9/28 작성분, 미추적) |
| `ops/README.md` | 수정 (스크립트 목록) |
| `.gitignore` | 수정 (`evidence/`, 9/28) |
| `docs/current/2026-09-28-150m-reproduction-set-removal-trace.md` | 미추적 → 추가 대상 (머리말에 후속 문서 링크) |
| `app/campaign-core/.../bridge/ParticipationBridge.java` | 수정 (9/28 LLEN 가드 + legacy 드레인 제거) |
| `app/campaign-core/.../scheduler/QueueMetricsScheduler.java` | 수정 |
| `app/campaign-core/src/test/.../bridge/ParticipationBridgeDeactivationTest.java` | 미추적 → 추가 대상 (9/28 신규 + 1건 추가) |
| `app/campaign-core/src/test/.../scheduler/QueueMetricsSchedulerTest.java` | 수정 |
| `docs/current/2026-10-03-ami-baked-container-remediation.md` | 신규 (이 문서) |

커밋 시 주의 (9/28 §8과 동일): `git add .` 금지. 같은 디렉터리에 사용자 로컬 미추적 파일(`bridge/ParticipationBridgeTest.java`, `StockRecoverySchedulerTest.java` 등)과 이전 변경(`docker-compose.test.yml` staged, `docs/blog/part5-…`, `infra/.terraform.lock.hcl`, `infra/errored.tfstate`)이 섞여 있다.
