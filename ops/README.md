# Ops Automation Runbook

이 디렉터리는 비용 절감을 위해 수동으로 켜고 끄던 AWS 부하테스트 환경을 Ansible로 자동화하기 위한 운영 문서와 playbook을 둔다.

첫 단계에서는 기존 수동 절차를 기준선으로 문서화했고, 이후 단계에서 이 문서의 순서를 그대로 Ansible playbook으로 옮긴다.

## 목표

- 환경 종료와 시작을 각각 하나의 명령으로 실행한다.
- Terraform, CodeDeploy, Ansible의 책임을 분리한다.
- Redis exporter 중복 실행처럼 Grafana 지표가 왜곡되는 실수를 줄인다.
- 서버를 추가하거나 RDS/Redis 사양을 바꿔도 운영 절차를 한 곳에서 추적할 수 있게 만든다.

## 책임 분리

| 영역 | 책임 | 현재 파일 |
| --- | --- | --- |
| Terraform | VPC, ALB, ASG, EC2, RDS, ElastiCache, SSM Parameter 같은 인프라 리소스 정의 | `infra/*.tf` |
| CodeDeploy | Spring Boot 애플리케이션 컨테이너 배포 | `app/campaign-core/appspec.yml`, `deploy/scripts/*.sh` |
| Ansible | 이미 존재하는 리소스의 on/off 순서 자동화, 원격 명령 실행, 상태 확인 | `ops/` |

Terraform은 "무엇을 만들 것인가"를 관리하고, Ansible은 "언제 어떤 순서로 켜고 끌 것인가"를 관리한다.

CodeDeploy 스크립트는 애플리케이션 배포 책임이므로 이번 lifecycle 자동화 범위에서는 유지한다. 나중에 배포 스크립트까지 Ansible 역할로 정리할 수 있지만, 오늘의 우선순위는 비용 절감용 환경 lifecycle 자동화다.

## 운영 대상 리소스

| 대상 | AWS/Terraform 이름 | 운영 방식 |
| --- | --- | --- |
| App ASG | `batch-kafka-app-asg`, `aws_autoscaling_group.app` | 종료 시 `min/max/desired=0`, 시작 시 `min=2/max=3/desired=2` |
| Kafka brokers | `kafka-1`, `kafka-2`, `kafka-3` | EC2 start/stop |
| Monitoring/MCP | `terraform-mcp`, `aws_instance.terraform_mcp` | EC2 start/stop, SSM 접속 후 redis-exporter 재기동 |
| RDS MySQL | `batch-kafka-db`, `aws_db_instance.batch_kafka_db` | RDS start/stop |
| Redis/Valkey | `batch-kafka-redis`, `aws_elasticache_replication_group.redis` | 비용 절감을 위해 Terraform targeted destroy/apply |
| Redis app SSM | `/batch-kafka/prod/SPRING_DATA_REDIS_CLUSTER_NODES` | Redis apply 후 endpoint 값 저장 |
| Redis exporter SSM | `/batch-kafka/prod/REDIS_EXPORTER_ADDR` | exporter가 읽을 `redis://...:6379` 주소 저장 |

기본 리전은 `ap-northeast-2`다.

## 종료 절차

현재 수동 종료 절차는 다음 순서를 기준으로 한다.

1. App ASG를 먼저 0으로 줄인다.
   - `desired=0`
   - `min=0`
   - `max=0`
   - 이유: 애플리케이션 인스턴스가 Redis, Kafka, RDS에 계속 연결을 시도하지 않게 만든다.

2. Kafka broker EC2를 중지한다.
   - 대상: `kafka-1`, `kafka-2`, `kafka-3`
   - 이유: 애플리케이션 트래픽을 먼저 제거한 뒤 broker를 내려야 불필요한 consumer/producer 오류를 줄일 수 있다.

3. `terraform-mcp` EC2를 중지한다.
   - 이유: Prometheus/Grafana/MCP 서버는 관측용이므로 테스트 환경 종료 시 함께 내린다.

4. RDS를 중지한다.
   - 대상: `batch-kafka-db`
   - 이유: DB 비용을 줄인다. RDS는 삭제하지 않고 stop/start로 운영한다.

5. Redis/Valkey와 Redis 관련 SSM parameter를 Terraform targeted destroy로 제거한다.

   ```bash
   terraform destroy -target=aws_elasticache_replication_group.redis
   terraform destroy -target=aws_ssm_parameter.redis_cluster_nodes
   terraform destroy -target=aws_ssm_parameter.redis_exporter_addr
   ```

   이유: ElastiCache는 stop 개념이 없으므로 비용 절감을 위해 삭제 후 재생성한다. Redis endpoint가 바뀔 수 있으므로 관련 SSM parameter도 Redis 생성 흐름에서 다시 맞춘다.

## 시작 절차

현재 수동 시작 절차는 다음 순서를 기준으로 한다.

1. Redis/Valkey와 Redis 관련 SSM parameter를 Terraform targeted apply로 생성한다.

   ```bash
   terraform apply \
     -target=aws_elasticache_replication_group.redis \
     -target=aws_ssm_parameter.redis_cluster_nodes \
     -target=aws_ssm_parameter.redis_exporter_addr
   ```

   Redis 생성은 보통 10~15분 정도 걸릴 수 있다. 이 시간은 자동화해도 줄어드는 시간이 아니라, 사람이 기다리며 명령을 반복 입력하지 않게 만드는 것이 핵심이다.

2. RDS를 시작하고 `available` 상태가 될 때까지 기다린다.
   - 대상: `batch-kafka-db`
   - 이유: 애플리케이션이 올라오기 전에 DB endpoint가 응답 가능한 상태여야 한다.

3. Kafka broker EC2를 시작한다.
   - 대상: `kafka-1`, `kafka-2`, `kafka-3`
   - 다음 단계에서는 EC2 state뿐 아니라 Kafka broker port/readiness 확인까지 자동화한다.

4. `terraform-mcp` EC2를 시작한다.
   - 이후 SSM command가 가능한 상태가 될 때까지 기다린다.

5. `terraform-mcp`에서 redis-exporter를 재기동한다.

   ```bash
   sudo docker rm -f redis-exporter
   sudo docker run -d --name redis-exporter --restart unless-stopped \
     --network monitoring -p 9121:9121 \
     -e REDIS_ADDR="$(aws ssm get-parameter \
       --name /batch-kafka/prod/REDIS_EXPORTER_ADDR \
       --with-decryption --query Parameter.Value --output text)" \
     -e REDIS_EXPORTER_IS_CLUSTER=true \
     oliver006/redis_exporter
   ```

   redis-exporter는 App ASG 인스턴스마다 띄우지 않고 `terraform-mcp`에서 하나만 실행한다. Grafana에서 Redis 지표가 두 번 집계되는 문제를 막기 위한 운영 결정이다.

6. App ASG를 다시 서비스 용량으로 올린다.
   - `desired=2`
   - `min=2`
   - `max=3`
   - Terraform의 `aws_autoscaling_group.app`에는 `desired_capacity`, `min_size`, `max_size`가 `ignore_changes`로 잡혀 있으므로, 운영 중 on/off 스케일 조정은 Ansible이 담당해도 Terraform drift로 되돌아가지 않는다.

## ALB 처리 방침

ALB는 stop/start가 불가능하다. 비용을 없애려면 삭제해야 하지만, 삭제하면 DNS name, listener, target group, CodeDeploy 연결이 같이 흔들릴 수 있다.

따라서 현재 자동화 1차 범위에서는 ALB를 유지한다. 비용은 계속 발생하지만, 테스트 환경을 자주 켜고 끄는 DX와 배포 안정성을 우선한다.

ALB 비용까지 줄이고 싶다면 별도 단계에서 다음 중 하나를 검토한다.

- ephemeral test environment를 별도 Terraform workspace로 만들어 전체 생성/삭제
- 고정 도메인과 Route 53 alias를 두고 ALB 재생성 자동화
- 부하테스트 기간에만 ALB를 생성하는 전용 environment profile

이 변경은 blast radius가 크므로 오늘의 Ansible lifecycle 자동화와 분리한다.

## 다음 단계 자동화 계획

| 단계 | 파일 | 목적 |
| --- | --- | --- |
| 2 | `ops/requirements.yml`, `ops/ansible.cfg`, `ops/inventory/aws_ec2.yml`, `ops/group_vars/all.yml` | Ansible 컬렉션, 실행 기본값, AWS dynamic inventory, 공통 변수 준비 |
| 3 | `ops/playbooks/env-status.yml` | 현재 환경 상태를 읽기 전용으로 점검 |
| 4 | `ops/playbooks/restart-redis-exporter.yml` | `terraform-mcp`에서 redis-exporter를 idempotent하게 재기동 |
| 5 | `ops/playbooks/env-down.yml` | 종료 절차 자동화 |
| 6 | `ops/playbooks/env-up.yml` | 시작 절차 자동화 |
| 7 | `Makefile` | `make env-status`, `make env-up`, `make env-down` 같은 DX 명령 제공 |

## 상태 점검 실행

`env-status.yml`은 AWS 상태를 읽기만 하는 점검 playbook이다. ASG, Kafka EC2, `terraform-mcp`, RDS, Redis replication group, Redis 관련 SSM parameter를 조회하고 요약한다.

```bash
cd ops
ansible-playbook -i localhost, playbooks/env-status.yml
```

다른 AWS profile을 사용하려면 실행 전에 `AWS_PROFILE`을 지정한다. Bash/WSL에서는 다음처럼 실행한다.

```bash
AWS_PROFILE=your-profile ansible-playbook -i localhost, playbooks/env-status.yml
```

PowerShell에서는 다음처럼 지정한다.

```powershell
$env:AWS_PROFILE = "your-profile"
ansible-playbook -i localhost, playbooks/env-status.yml
```

## Redis exporter 재기동

`restart-redis-exporter.yml`은 `terraform-mcp` EC2에 SSM SendCommand를 보내서 `redis-exporter` 컨테이너를 재기동한다. 이 playbook은 Docker 컨테이너 상태를 실제로 바꾼다.

```bash
cd ops
ansible-playbook -i localhost, playbooks/restart-redis-exporter.yml
```

PowerShell에서 profile을 지정하려면 다음처럼 실행한다.

```powershell
$env:AWS_PROFILE = "your-profile"
ansible-playbook -i localhost, playbooks/restart-redis-exporter.yml
```

이 playbook은 로컬에서 `/batch-kafka/prod/REDIS_EXPORTER_ADDR` 값을 읽고, SSM SendCommand로 `terraform-mcp`에서 다음 흐름을 실행한다.

1. 기존 `redis-exporter` 컨테이너 제거
2. 새 Redis endpoint를 `REDIS_ADDR`로 주입
3. `oliver006/redis_exporter` 컨테이너를 `monitoring` Docker network에 하나만 실행

## 안전 원칙

- destructive 작업은 `env-down`에만 둔다.
- Redis targeted destroy/apply는 Terraform state를 사용하는 로컬 명령으로 유지한다.
- 시작 절차는 각 리소스가 준비 상태가 된 뒤 다음 단계로 넘어간다.
- redis-exporter는 항상 기존 컨테이너를 제거한 뒤 하나만 실행한다.
- 첫 번째 Ansible playbook은 반드시 읽기 전용 상태 점검부터 만든다.

## 장애 시 확인 포인트

| 증상 | 먼저 볼 것 |
| --- | --- |
| App이 Redis에 붙지 못함 | `/batch-kafka/prod/SPRING_DATA_REDIS_CLUSTER_NODES` 값이 새 Redis endpoint인지 확인 |
| Grafana Redis 지표가 두 배로 보임 | `redis-exporter`가 App 인스턴스와 `terraform-mcp` 양쪽에서 실행 중인지 확인 |
| redis-exporter가 뜨지 않음 | `terraform-mcp`가 SSM command 가능한 상태인지, Docker network `monitoring`이 존재하는지 확인 |
| App ASG가 다시 켜지지 않음 | ASG capacity 값과 Launch Template/CodeDeploy agent 상태 확인 |
