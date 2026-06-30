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
| `make env-status` | ASG, EC2, RDS, Redis, SSM 상태를 읽기 전용으로 조회 | 없음 |
| `make env-down` | 비용 절감을 위해 테스트 환경 종료 | ASG 축소, EC2 stop, RDS stop과 Redis targeted destroy 병렬 진행 |
| `make env-up` | 테스트 환경 기동 | RDS start와 Redis targeted apply 병렬 진행, EC2 start, redis-exporter 재기동, ASG 복구 |
| `make redis-exporter` | `terraform-mcp`에서 redis-exporter 컨테이너 재기동 | Docker 컨테이너 교체 |

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
| `BeforeInstall` | `before_install` | ECR login, `/opt/campaign-core` 준비, SSM Parameter 기반 `.env.prod` 생성 |
| `AfterInstall` | `after_install` | `.env.prod`에서 `ECR_IMAGE` 확인, Docker image pull |
| `ApplicationStart` | `application_start` | compose 파일 배치, stress-test 스크립트 동기화, 기존 컨테이너 중지, 새 컨테이너 실행 |
| `ValidateService` | `validate_service` | actuator health check, 실패 시 Ansible task 로그 출력 |

배포 번들에는 `appspec.yml`, `deploy/`, `ops/`, `stress-test/`가 포함된다. 따라서 앱 서버에 별도의 playbook checkout이 없어도 CodeDeploy archive 안의 playbook을 그대로 실행한다.

문법 확인은 `ops` 디렉터리에서 실행한다.

```bash
ANSIBLE_CONFIG=./ansible.cfg ansible-playbook -i inventory/localhost.yml playbooks/deploy-app.yml --syntax-check
```

## 관리 대상 리소스

| 대상 | AWS/Terraform 이름 | 운영 방식 |
| --- | --- | --- |
| App ASG | `batch-kafka-app-asg`, `aws_autoscaling_group.app` | 종료 시 `min/max/desired=0`, 기동 시 `min=2/max=3/desired=2` |
| Kafka brokers | `kafka-1`, `kafka-2`, `kafka-3` | EC2 start/stop |
| Monitoring/MCP | `terraform-mcp`, `aws_instance.terraform_mcp` | EC2 start/stop, SSM SendCommand로 redis-exporter 재기동 |
| RDS MySQL | `batch-kafka-db`, `aws_db_instance.batch_kafka_db` | RDS start/stop |
| Redis/Valkey | `batch-kafka-redis`, `aws_elasticache_replication_group.redis` | 비용 절감을 위해 Terraform targeted destroy/apply |
| Redis app SSM | `/batch-kafka/prod/SPRING_DATA_REDIS_CLUSTER_NODES` | Redis endpoint 저장 |
| Redis exporter SSM | `/batch-kafka/prod/REDIS_EXPORTER_ADDR` | `redis://...:6379` exporter 주소 저장 |

기본 리전은 `ap-northeast-2`다.

## 종료 흐름

`make env-down`은 기존 수동 종료 절차를 자동화한다.

1. App ASG를 `min=0`, `max=0`, `desired=0`으로 축소한다.
2. `kafka-1`, `kafka-2`, `kafka-3` EC2를 중지한다.
3. `terraform-mcp` EC2를 중지한다.
4. `batch-kafka-db` RDS stop 요청을 보낸다.
5. Redis/Valkey와 Redis 관련 SSM Parameter를 Terraform targeted destroy로 제거한다.
6. RDS가 `stopped`가 될 때까지 확인한다.

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
7. `terraform-mcp`에서 redis-exporter 컨테이너를 새 Redis endpoint로 재기동한다.
8. App ASG를 `min=2`, `max=3`, `desired=2`로 복구하고 인스턴스가 `InService`가 될 때까지 기다린다.

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
- ALB는 stop/start가 불가능하고 삭제하면 DNS, listener, target group, CodeDeploy 연결 영향이 크므로 현재 자동화 범위에서 제외한다.

## 문제 해결

| 증상 | 확인할 것 |
| --- | --- |
| `aws`를 찾지 못함 | WSL 안에 AWS CLI가 설치되어 있는지 확인 |
| `terraform`을 찾지 못함 | WSL 안에 Terraform이 설치되어 있는지 확인 |
| Ansible이 `ansible.cfg`를 무시함 | `/mnt/c` 경로 world-writable 이슈이므로 `ANSIBLE_CONFIG=./ansible.cfg`를 명시 |
| Redis exporter 재기동 실패 | `terraform-mcp`가 running인지, SSM Agent가 Online인지 확인 |
| App이 Redis에 붙지 못함 | `/batch-kafka/prod/SPRING_DATA_REDIS_CLUSTER_NODES` 값이 새 Redis endpoint인지 확인 |
| Grafana Redis 지표가 두 배로 보임 | App 인스턴스에서 redis-exporter가 추가로 떠 있지 않은지 확인 |

## 다음 작업

- CodeDeploy 배포 스크립트와 Ansible 역할 분리 재검토
- WSL 기반 운영 자동화 세팅 과정 블로그화
- Ansible playbook 도입 과정 블로그화
