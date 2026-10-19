# Ops Automation Runbook

비용 절감을 위해 수동으로 켜고 끄던 AWS 부하 테스트 환경을 Ansible playbook과 Makefile로 자동화한다.

이 디렉터리는 인프라를 새로 정의하는 곳이 아니라, 이미 Terraform으로 만든 리소스의 운영 lifecycle을 자동화하는 곳이다.

## 역할 분리

| 영역 | 책임 | 위치 |
| --- | --- | --- |
| Terraform | VPC, ALB, ASG, EC2, RDS, ElastiCache, SSM Parameter 같은 인프라 리소스 정의 | `infra/` |
| CodeDeploy | Spring Boot 애플리케이션 배포 트리거와 배포 번들 전달 | `app/campaign-core/appspec.yml` |
| Ansible | 기존 리소스의 상태 점검, 비용 절감 종료, 테스트 환경 기동, SSM 원격 명령, 서버 내부 애플리케이션 배포 절차 자동화 | `ops/` |

Terraform은 "무엇을 만들 것인가"를 관리하고, Ansible은 "언제 어떤 순서로 켜고 끌 것인가"를 관리한다.

## 실행 환경

Ansible control node는 WSL Ubuntu를 기준으로 한다. Git Bash가 틀린 것은 아니지만, 이 프로젝트의 운영 자동화는 WSL에서 `ansible-playbook`, `aws`, `terraform`을 실행하는 방식으로 고정한다.

```bash
cd /mnt/c/Users/user/Desktop/1milion-campaign-orchestration-system/ops
```

필요한 로컬 CLI:

- `ansible-playbook`
- `ansible-galaxy`
- `aws`
- `terraform`
- `make`

처음 한 번 또는 도구 점검이 필요할 때:

```bash
make check-tools
make requirements
type -a aws
aws --version
aws sts get-caller-identity
terraform version
```

WSL의 `/mnt/c` 경로는 world-writable로 인식될 수 있으므로 Makefile은 `ANSIBLE_CONFIG=./ansible.cfg`를 명시해서 실행한다.

## 주요 명령

```bash
make help
make env-status
make env-down
make env-up
make redis-exporter
```

| 명령 | 설명 | 실제 변경 |
| --- | --- | --- |
| `make env-status` | ASG, ALB, EC2, RDS, Redis, SSM 상태를 읽기 전용으로 조회 | 없음 |
| `make env-down` | 비용 절감을 위해 테스트 환경 종료 | ASG 축소 후 ALB/Listener 제거, EC2 stop, RDS stop과 Redis targeted destroy 병렬 진행 |
| `make env-up` | 테스트 환경 기동 | RDS start와 Redis targeted apply 병렬 진행, EC2 start, ALB/Listener 생성, redis-exporter 재기동, ASG 복구 |
| `make redis-exporter` | `terraform-mcp`에서 redis-exporter 컨테이너 재기동 | Docker 컨테이너 교체 |

### 운영·증거 수집 스크립트 (`ops/scripts/`)

| 스크립트 | 설명 | 실제 변경 |
| --- | --- | --- |
| `redis-watch-start.sh <campaignId>` / `redis-watch-fetch.sh [--stop]` | terraform-mcp에서 Redis 상태(active Set, flag, LLEN, 재고) 1초 기록 시작 / 회수 | terraform-mcp에 기록용 컨테이너 |
| `collect-app-logs.sh [dir]` | ASG 전 인스턴스(또는 `INSTANCE_IDS`) 앱 로그 수집 | 없음 |
| `capture-boot-containers.sh <instance>` | 새 인스턴스 부팅 시 현재 배포 이미지가 아닌 컨테이너의 메타·로그 저장 | 대상 인스턴스 `/tmp/boot-capture/` |
| `rebuild-app-ami.sh` | 잔존 컨테이너·이미지 없는 앱 AMI 재생성. 기본은 계획만 출력 | `CONFIRM=yes`일 때 임시 EC2 기동→AMI 생성→종료 |
| `close-stale-campaigns.sh` | DB에 OPEN으로 남은 과거 캠페인 조회 / CLOSED 처리 (`MAX_ID` 필수) | `CONFIRM=yes`일 때 DB UPDATE |
| `terraform-alb.sh` | env-up/down에서 저장된 Terraform plan의 변경 대상을 검사한 뒤 적용 | ALB·Listener 생성/삭제, 기동 시 MCP의 ALB 조회 IAM 정책 생성 |

배경: `docs/reports/2026-09-28-zombie-container-root-cause.md`, `docs/reports/2026-10-03-ami-container-remediation.md`

## CodeDeploy 애플리케이션 배포 흐름

CodeDeploy는 배포 lifecycle을 시작하고 S3 배포 번들을 앱 서버에 전달하는 역할에 집중한다. 서버 내부에서 수행되는 실제 배포 절차는 `ops/playbooks/deploy-app.yml`이 담당한다.

현재 구조는 다음과 같다.

```text
app/campaign-core/appspec.yml
  └─ deploy/scripts/run-ansible-deploy.sh
       └─ ops/playbooks/deploy-app.yml
```

`appspec.yml`의 각 lifecycle hook은 같은 wrapper를 호출한다. wrapper는 CodeDeploy가 전달하는 `LIFECYCLE_EVENT` 값을 Ansible tag로 변환해서 필요한 task 묶음만 실행한다.

| CodeDeploy lifecycle | Ansible tag | 주요 작업 |
| --- | --- | --- |
| `BeforeInstall` | `before_install` | ECR login, `/opt/campaign-core` 준비, SSM 환경 설정과 배포 번들의 고정 이미지 digest로 `.env.prod` 생성 |
| `AfterInstall` | `after_install` | `.env.prod`에서 고정된 `ECR_IMAGE` 확인, Docker image pull |
| `ApplicationStart` | `application_start` | compose 파일 배치, stress-test 스크립트 동기화, 기존 컨테이너 중지, 새 컨테이너 실행 |
| `ValidateService` | `validate_service` | actuator health와 실행 컨테이너 이미지가 배포 번들의 digest와 일치하는지 확인 |

배포 번들에는 `appspec.yml`, `image-revision.env`, `deploy/`, `ops/`, `stress-test/`가 포함된다. `image-revision.env`는 CI가 생성하며 Git SHA와 ECR 이미지 digest를 함께 고정한다. 서버는 이 번들의 이미지로 기동하므로 이전 번들을 재배포할 때도 공용 SSM의 최신 이미지 포인터를 따라가지 않는다. 앱 서버에 별도의 playbook checkout이 없어도 CodeDeploy archive 안의 playbook을 그대로 실행한다.

같은 Git SHA에서 워크플로를 재실행해도 이전 번들이 덮어써지지 않도록 S3 객체 키에 GitHub 실행 ID와 재시도 번호를 포함한다. GitHub job을 취소해도 이미 시작된 CodeDeploy가 자동 중지되는 것은 아니므로 배포 job은 서로 겹치지 않게 직렬화한다. **롤백 가능한 기간 동안 ECR 이미지 digest와 S3 번들을 삭제하지 않아야 한다.** 현재 ECR 보존 정책 자체는 이 변경에 포함하지 않는다.

CI는 CodeDeploy 상태를 15초 간격으로 확인하고 성공 상태에서만 이미지 포인터를 갱신한다. 실패·중지·알 수 없는 상태는 실패로 처리하며, 120분 안에 끝나지 않으면 CI도 실패한다. 정상 배포 시간은 늘어나지 않지만, 장시간 지연 시 GitHub runner가 그동안 점유되고 뒤따르는 배포가 대기한다. 타임아웃이 발생했다면 CodeDeploy의 최종 상태와 SSM 포인터를 수동으로 대조해야 한다.

`/batch-kafka/prod/ECR_IMAGE`는 **배포 성공 후** 갱신하는 호환·진단 포인터이며 새 번들의 이미지 선택에는 사용하지 않는다. **최초 전환 배포 전에 이 SSM 값이 이전 정상 이미지와 일치하는지 확인해야 한다.** 과거에 서버가 꺼진 동안 실패한 CI가 SSM 값을 먼저 바꿨다면 포인터가 이미 틀렸을 수 있다. 정상 값임을 확인했다면 최초 전환 배포 실패 시 이전 번들의 playbook이 그 이미지를 읽어 롤백할 수 있다. 배포 성공 후에는 이전 방식의 번들이 최신 포인터를 읽을 수 있으므로 **최초 전환 이후의 롤백 대상은 고정 이미지 파일이 포함된 성공 번들인지 확인**해야 한다. CodeDeploy 롤백은 DB/Redis의 데이터 변경까지 되돌리지 않는다.

문법 확인은 `ops` 디렉터리에서 실행한다.

```bash
ANSIBLE_CONFIG=./ansible.cfg ansible-playbook -i inventory/localhost.yml playbooks/deploy-app.yml --syntax-check
```

## 관리 대상 리소스

| 대상 | AWS/Terraform 이름 | 운영 방식 |
| --- | --- | --- |
| App ASG | `batch-kafka-app-asg`, `aws_autoscaling_group.app` | 종료 시 `min/max/desired=0`, 기동 시 `min=2/max=3/desired=2` |
| ALB/Listener | `alb-batch-kafka-api`, `aws_lb.main[0]`, `aws_lb_listener.http[0]` | 앱 ASG가 0대가 된 뒤 삭제, 앱 기동 전에 재생성 |
| Kafka brokers | `kafka-1`, `kafka-2`, `kafka-3` | EC2 start/stop |
| Monitoring/MCP | `terraform-mcp`, `aws_instance.terraform_mcp` | EC2 start/stop, SSM SendCommand로 redis-exporter 재기동 |
| RDS MySQL | `batch-kafka-db`, `aws_db_instance.batch_kafka_db` | RDS start/stop |
| Redis/Valkey | `batch-kafka-redis`, `aws_elasticache_replication_group.redis` | 비용 절감을 위해 Terraform targeted destroy/apply |
| Redis app SSM | `/batch-kafka/prod/SPRING_DATA_REDIS_CLUSTER_NODES` | Redis endpoint 저장 |
| Redis exporter SSM | `/batch-kafka/prod/REDIS_EXPORTER_ADDR` | `redis://...:6379` exporter 주소 저장 |

기본 리전은 `ap-northeast-2`다.

## 종료 흐름

`make env-down`은 기존 수동 종료 절차를 자동화한다.

`env-up`과 `env-down`은 시작 전에 현재 AWS 계정 ID 및 `terraform-mcp` EC2의 VPC/서브넷 ID를 조회해 Terraform의 필수 입력값으로 전달한다. 따라서 로컬 `terraform.tfvars`나 `TF_VAR_*` 환경변수가 없어도 실행할 수 있다. Redis용 SSM Parameter Store 값은 Terraform 입력값이 아니라, Redis 생성 후 앱과 exporter에 전달할 endpoint다.

1. App ASG를 `min=0`, `max=0`, `desired=0`으로 축소하고 인스턴스가 0대인지 확인한다.
2. ALB와 HTTP Listener만 저장된 Terraform plan으로 삭제한다. Target Group은 유지한다.
3. `kafka-1`, `kafka-2`, `kafka-3` EC2를 중지한다.
4. `terraform-mcp` EC2를 중지한다.
5. `batch-kafka-db` RDS stop 요청을 보낸다.
6. Redis/Valkey와 Redis 관련 SSM Parameter를 Terraform targeted destroy로 제거한다.
7. RDS가 `stopped`가 될 때까지 확인한다.

RDS stop과 Redis targeted destroy는 서로 의존하지 않는 장기 작업이므로 같은 구간에서 진행되게 구성했다. App ASG는 가장 먼저 내리고, Kafka와 `terraform-mcp`는 기존 순서를 유지한다.

내부적으로 destructive 작업 방지를 위해 `confirm_env_down=true`가 필요하며, Makefile이 이 값을 명시해서 전달한다.

직접 playbook을 실행해야 한다면:

```bash
ANSIBLE_CONFIG=./ansible.cfg ansible-playbook -i inventory/localhost.yml playbooks/env-down.yml -e confirm_env_down=true
```

## 기동 흐름

`make env-up`은 기존 수동 기동 절차를 자동화한다.

1. RDS start 요청을 보낸다.
2. Redis/Valkey와 Redis 관련 SSM Parameter를 Terraform targeted apply로 재생성한다.
3. Redis replication group이 `available`이 될 때까지 기다린다.
4. RDS가 `available`이 될 때까지 기다린다.
5. Kafka broker EC2 3대를 시작하고 `running`이 될 때까지 기다린다.
6. `terraform-mcp`를 시작하고 SSM Agent가 `Online`이 될 때까지 기다린다.
7. ALB와 HTTP Listener만 저장된 Terraform plan으로 생성하고 새 `alb_base_url`을 출력한다.
8. `terraform-mcp`에서 redis-exporter 컨테이너를 새 Redis endpoint로 재기동한다.
9. App ASG를 `min=2`, `max=3`, `desired=2`로 복구하고 인스턴스가 `InService`가 될 때까지 기다린다.

RDS start와 Redis targeted apply는 서로 의존하지 않는 장기 작업이므로 같은 구간에서 진행되게 구성했다. Kafka와 `terraform-mcp`는 기존 순서를 유지하고, App ASG는 모든 의존 리소스와 redis-exporter가 준비된 뒤 마지막에 복구한다.

내부적으로 비용 발생 작업 방지를 위해 `confirm_env_up=true`가 필요하며, Makefile이 이 값을 명시해서 전달한다.

직접 playbook을 실행해야 한다면:

```bash
ANSIBLE_CONFIG=./ansible.cfg ansible-playbook -i inventory/localhost.yml playbooks/env-up.yml -e confirm_env_up=true
```

## redis-exporter 운영

redis-exporter는 App ASG 인스턴스마다 띄우지 않고 `terraform-mcp`에서 하나만 실행한다. Grafana에서 Redis 지표가 중복 집계되는 문제를 막기 위한 운영 결정이다.

`make redis-exporter`는 다음 작업을 SSM SendCommand로 수행한다.

1. 기존 `redis-exporter` 컨테이너 제거
2. Parameter Store의 `/batch-kafka/prod/REDIS_EXPORTER_ADDR` 조회
3. `oliver006/redis_exporter` 컨테이너를 `monitoring` Docker network에 하나만 실행

## 안전 장치

- `env-status`는 읽기 전용이다.
- `env-down`은 `confirm_env_down=true` 없이는 실행되지 않는다.
- `env-up`은 `confirm_env_up=true` 없이는 실행되지 않는다.
- Redis는 ElastiCache 특성상 stop이 불가능하므로 Terraform targeted destroy/apply만 수행한다.
- ASG 용량은 Terraform에서 `ignore_changes`로 운영 중 조정이 허용되어 있어 Ansible이 on/off 용량을 바꿔도 Terraform drift로 되돌리지 않는다.
- ALB는 stop/start가 불가능하므로 ALB와 Listener만 재생성한다. Target Group ARN과 ASG 연결은 유지한다.
- ALB 작업은 Redis Terraform 작업과 순차 실행하며, 저장된 plan에 ALB·Listener 및 기동 시 MCP 읽기 전용 IAM 정책 이외의 변경이나 교체가 있으면 적용 전에 중단한다. `-target`은 전체 drift 검사를 대신하지 않는다.
- ALB가 재생성되면 DNS가 바뀐다. `stress-test/resolve-base-url.sh`와 새 버전의 `mcp-monitor`는 현재 ALB를 매번 조회한다. `terraform_mcp_alb_describe` IAM 정책을 적용하고 MCP 이미지를 새 코드로 한 번 재배포해야 한다. 기존 컨테이너는 여전히 예전 `BATCH_API_URL`을 사용할 수 있다.

## 문제 해결

| 증상 | 확인할 것 |
| --- | --- |
| `aws`를 찾지 못함 | WSL 안에 AWS CLI가 설치되어 있는지 확인 |
| SSM `send-command`가 `badly formed help string`으로 실패 | `type -a aws`, `aws --version`으로 오래된 `/usr/bin/aws`가 우선되지 않는지 확인하고 `/usr/local/bin`을 PATH 앞에 둔다 |
| `terraform`을 찾지 못함 | WSL 안에 Terraform이 설치되어 있는지 확인 |
| Ansible이 `ansible.cfg`를 무시함 | `/mnt/c` 경로 world-writable 이슈이므로 `ANSIBLE_CONFIG=./ansible.cfg`를 명시 |
| Redis exporter 재기동 실패 | `terraform-mcp`가 running인지, SSM Agent가 Online인지 확인 |
| App이 Redis에 붙지 못함 | `/batch-kafka/prod/SPRING_DATA_REDIS_CLUSTER_NODES` 값이 새 Redis endpoint인지 확인 |
| Grafana Redis 지표가 두 배로 보임 | App 인스턴스에서 redis-exporter가 추가로 떠 있지 않은지 확인 |
| 신규 ASG 인스턴스가 `Pending:Wait` 후 반복 종료 | CodeDeploy deployment target 로그와 `/var/log/cloud-init-output.log`를 함께 확인한다. `BeforeInstall`은 cloud-init 완료 후 Ansible을 실행해야 한다 |
