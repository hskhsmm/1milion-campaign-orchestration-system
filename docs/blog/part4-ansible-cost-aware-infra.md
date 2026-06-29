# 비용 절감을 위해 수동으로 끄고 켜던 AWS 인프라를 Ansible로 표준화한 기록

## 왜 이 작업을 하게 되었나

이 프로젝트는 단순한 CRUD 서비스가 아니라, 대규모 이벤트성 트래픽을 처리하는 캠페인 오케스트레이션 시스템이다.

Kafka 3 broker, Redis/Valkey Cluster, RDS MySQL, Auto Scaling Group, ALB, CodeDeploy, Prometheus/Grafana 기반 모니터링까지 포함해 실제 운영 환경에 가까운 구조로 AWS 인프라를 구성했다.

문제는 비용이었다.

개인 프로젝트에서 운영형 인프라를 계속 켜두면 비용 부담이 꽤 커진다. 특히 다음 리소스들은 테스트하지 않는 시간에도 계속 비용이 발생한다.

- RDS MySQL
- ElastiCache Redis/Valkey Cluster
- Kafka EC2 3대
- monitoring 및 terraform-mcp EC2
- Auto Scaling Group의 애플리케이션 EC2
- ALB

ALB처럼 항상 유지해야 하는 리소스도 있지만, RDS, Redis, Kafka, 애플리케이션 서버는 테스트하지 않을 때 꺼둘 수 있다.

그래서 기존에는 테스트가 끝나면 수동으로 인프라를 내리고, 다시 테스트할 때 하나씩 켜는 방식으로 운영했다.

처음에는 비용을 아끼기 위한 현실적인 선택이었다. 하지만 시간이 지나면서 이 수동 절차 자체가 프로젝트를 다시 시작하는 데 큰 부담이 되었다.

## 기존 수동 운영 방식

기존 종료 절차는 대략 다음과 같았다.

```text
1. ASG desired=0, min=0, max=0
2. kafka-1, kafka-2, kafka-3 EC2 중지
3. terraform-mcp EC2 중지
4. RDS 중지
5. 로컬에서 Redis 관련 Terraform targeted destroy 실행
   - aws_elasticache_replication_group.redis
   - aws_ssm_parameter.redis_cluster_nodes
   - aws_ssm_parameter.redis_exporter_addr
```

다시 켤 때는 반대로 진행했다.

```text
1. 로컬에서 Redis 관련 Terraform targeted apply 실행
2. RDS 시작
3. kafka-1, kafka-2, kafka-3 EC2 시작
4. terraform-mcp EC2 시작
5. terraform-mcp에 SSM 접속
6. redis-exporter Docker 컨테이너 재실행
7. ASG desired=2, min=2, max=3 복구
```

이 방식은 비용을 줄이는 데는 효과적이었다. 하지만 운영 경험은 좋지 않았다.

매번 AWS 콘솔과 로컬 터미널을 오가며 명령을 입력해야 했고, 순서를 틀리면 애플리케이션이 정상적으로 올라오지 않았다. Redis는 재생성될 때 endpoint가 바뀔 수 있기 때문에 SSM Parameter 갱신과 redis-exporter 재시작도 함께 신경 써야 했다.

결국 인프라를 끄는 것은 비용 절감에 도움이 되었지만, 다시 켜는 과정이 번거로워서 테스트 자체를 미루게 되는 문제가 생겼다.

## 단순 스크립트가 아니라 Ansible을 선택한 이유

처음에는 shell script로도 해결할 수 있다고 생각했다.

실제로 AWS CLI와 Terraform 명령어를 순서대로 실행하는 것만 보면 shell script로 충분해 보인다.

하지만 이번 작업의 목표는 단순히 명령어를 한 파일에 모아두는 것이 아니었다.

내가 원했던 것은 다음에 가까웠다.

- 각 단계의 목적이 코드에 드러날 것
- 실행 순서가 명확할 것
- 상태 확인과 대기 로직이 포함될 것
- 실패 지점을 파악하기 쉬울 것
- 나중에 서버가 늘어나도 구조를 확장할 수 있을 것
- 인프라 운영 절차를 문서처럼 읽을 수 있을 것

이 기준에서는 shell script보다 Ansible이 더 잘 맞았다.

Ansible은 task 단위로 작업을 선언할 수 있고, 각 task에 이름을 붙일 수 있다. 또한 `when`, `register`, `until`, `retries`, `delay` 같은 기능을 통해 상태 기반 자동화를 구성하기 좋다.

특히 이번 작업처럼 "AWS 리소스 상태를 확인하고, 필요한 경우 시작 또는 중지하고, 완료될 때까지 기다리는" 운영 자동화에는 Ansible playbook이 잘 맞았다.

## Terraform과 Ansible의 역할 분리

이번 작업에서 가장 중요하게 생각한 부분은 Terraform과 Ansible의 책임을 섞지 않는 것이었다.

Terraform은 인프라 리소스를 선언하고 생성하는 데 강하다.

예를 들어 다음 리소스들은 Terraform이 관리한다.

- VPC
- EC2
- RDS
- ElastiCache Redis/Valkey
- ALB
- Auto Scaling Group
- IAM
- SSM Parameter

반면 매일 반복하는 운영 작업은 Terraform만으로 관리하기 애매하다.

- EC2 start/stop
- RDS start/stop
- ASG desired/min/max 조정
- AWS 리소스 상태 확인
- 원격 서버에서 Docker 컨테이너 재시작
- 특정 순서에 따라 인프라를 끄고 켜기

그래서 역할을 다음처럼 나누었다.

```text
Terraform:
- 인프라 리소스 정의
- 리소스 생성 및 변경 관리
- Redis처럼 stop 개념이 없는 리소스의 targeted apply/destroy

Ansible:
- 비용 절감을 위한 운영 절차 자동화
- AWS CLI와 Terraform 실행 순서 표준화
- SSM을 통한 원격 명령 실행
- 상태 확인과 대기 로직 관리
- 반복 운영 명령을 하나의 playbook으로 통합
```

즉, Terraform은 "무엇을 만들 것인가"를 관리하고, Ansible은 "운영 중 어떤 순서로 무엇을 할 것인가"를 관리한다.

## 최종 목표

이번 자동화의 목표는 명확했다.

```text
인프라 종료:
make env-down

인프라 시작:
make env-up

상태 확인:
make env-status
```

반복적으로 수행하던 수동 작업을 하나의 명령어로 실행할 수 있게 만드는 것이 핵심이었다.

그리고 단순히 실행만 되는 것이 아니라, 다음 조건을 만족해야 했다.

- 잘못된 AWS 계정에 붙어 있으면 바로 확인할 수 있어야 한다.
- 실수로 종료하지 않도록 명시적인 확인값이 필요해야 한다.
- RDS와 Redis처럼 오래 걸리는 작업은 가능한 한 대기 시간을 줄여야 한다.
- Redis endpoint가 바뀌어도 SSM Parameter와 exporter가 함께 갱신되어야 한다.
- 애플리케이션 ASG는 의존 리소스가 준비된 뒤 마지막에 올라와야 한다.
- playbook만 읽어도 어떤 운영 절차인지 이해할 수 있어야 한다.

## Ansible 디렉토리 구조

이번 작업으로 `ops/` 디렉토리 아래에 Ansible 운영 자동화 구조를 만들었다.

```text
ops/
  ansible.cfg
  requirements.yml
  Makefile
  inventory/
    localhost.yml
    aws_ec2.yml
  group_vars/
    all.yml
  playbooks/
    env-status.yml
    env-down.yml
    env-up.yml
    restart-redis-exporter.yml
```

각 파일의 역할은 다음과 같다.

| 파일 | 역할 |
| --- | --- |
| `ansible.cfg` | Ansible 기본 설정 |
| `requirements.yml` | 필요한 Ansible collection 정의 |
| `Makefile` | 자주 쓰는 실행 명령 단순화 |
| `inventory/localhost.yml` | 로컬 control node 실행용 inventory |
| `inventory/aws_ec2.yml` | AWS EC2 dynamic inventory 기반 확장 여지 |
| `group_vars/all.yml` | 공통 변수 관리 |
| `playbooks/env-status.yml` | 현재 AWS 테스트 환경 상태 확인 |
| `playbooks/env-down.yml` | 비용 절감을 위한 인프라 종료 |
| `playbooks/env-up.yml` | 테스트 환경 재시작 |
| `playbooks/restart-redis-exporter.yml` | terraform-mcp에서 redis-exporter 재기동 |

## 왜 hosts가 localhost인가

playbook을 보면 대부분 다음처럼 시작한다.

```yaml
- name: Stop cost-aware test environment
  hosts: localhost
  connection: local
  gather_facts: false
```

처음 보면 이상해 보일 수 있다.

"AWS 인프라를 제어하는데 왜 localhost에서 실행하지?"라는 의문이 생긴다.

이 구조에서 localhost는 작업 대상 서버라기보다 제어 노드다.

내 로컬 WSL 환경에서 AWS CLI와 Terraform을 실행하고, 그 명령들이 AWS API를 호출한다. 즉, Ansible이 EC2에 직접 SSH로 접속해서 모든 작업을 수행하는 것이 아니라, 로컬을 control node로 사용해 AWS 리소스를 제어하는 구조다.

```text
WSL localhost
  ├─ aws ec2 start/stop-instances
  ├─ aws rds start/stop-db-instance
  ├─ aws autoscaling update-auto-scaling-group
  ├─ terraform apply/destroy
  └─ aws ssm send-command

AWS
  ├─ ASG
  ├─ EC2
  ├─ RDS
  ├─ Redis/Valkey
  └─ SSM                                                                                                                                                                          
```

이 방식의 장점은 별도의 Ansible 관리 서버가 필요 없다는 점이다.

로컬에 AWS CLI, Terraform, Ansible만 준비되어 있으면 동일한 playbook으로 반복 운영이 가능하다.

## env-status: 자동화 전 상태 확인

가장 먼저 만든 playbook은 `env-status.yml`이다.

인프라를 끄고 켜는 자동화를 만들기 전에, 현재 상태를 정확히 볼 수 있어야 했다.

이 playbook은 다음 정보를 확인한다.

- AWS caller identity
- AWS account
- region
- ASG desired/min/max
- ASG instance 상태
- Kafka EC2 상태
- terraform-mcp EC2 상태
- RDS 상태
- Redis replication group 상태
- Redis 관련 SSM Parameter
- terraform-mcp의 SSM Agent online 여부

실제로 확인한 상태 요약은 다음과 같은 형태였다.

```json
{
  "aws": {
    "account": "631124976154",
    "region": "ap-northeast-2",
    "user_arn": "arn:aws:iam::631124976154:user/batch-kafka-deploy"
  },
  "app_asg": {
    "Name": "batch-kafka-app-asg",
    "Desired": 2,
    "Min": 2,
    "Max": 3
  },
  "rds": {
    "Identifier": "batch-kafka-db",
    "Status": "available"
  },
  "redis": {
    "Id": "batch-kafka-redis",
    "Status": "available"
  }
}
```

운영 자동화에서 상태 확인은 단순한 보조 기능이 아니다.

특히 AWS 계정과 리전을 잘못 잡고 실행하면 실제 비용과 장애로 이어질 수 있다. 그래서 env-status를 먼저 만들고, env-up/env-down에서도 caller identity를 확인하도록 했다.

## env-down: 비용 절감을 위한 종료 자동화

`env-down.yml`은 테스트 환경을 끄는 playbook이다.

핵심 흐름은 다음과 같다.

```text
1. confirm_env_down=true 값 확인
2. AWS caller identity 확인
3. 애플리케이션 ASG desired/min/max를 0으로 조정
4. ASG 인스턴스가 종료될 때까지 대기
5. Kafka EC2 3대 중지
6. terraform-mcp EC2 중지
7. RDS stop 요청
8. Redis Terraform targeted destroy 실행
9. RDS stopped 상태 확인
10. 최종 요약 출력
```

실제로는 실수로 인프라를 내려버리는 일을 막기 위해 확인 변수를 강제했다. 이 playbook은 비용 절감용이지만 실제 AWS 리소스의 상태를 바꾸기 때문에, 명령어에 `confirm_env_down=true`가 없으면 바로 실패하도록 했다.

```yaml
- name: Require explicit env-down confirmation
  ansible.builtin.assert:
    that:
      - confirm_env_down | default(false) | bool
    fail_msg: "env-down은 실제 리소스를 중지/삭제합니다. -e confirm_env_down=true 를 붙여 다시 실행하세요."
```

### ASG를 가장 먼저 내리는 이유

종료할 때 ASG를 가장 먼저 내리는 이유는 애플리케이션이 의존 리소스에 계속 연결을 시도하지 않게 하기 위해서다.

애플리케이션 서버가 살아 있는 상태에서 Redis, RDS, Kafka가 먼저 내려가면 불필요한 에러 로그가 발생하고, 배치나 consumer 동작도 예측하기 어려워진다.

그래서 종료 순서는 다음 기준을 따른다.

```text
종료:
애플리케이션 서버 → 메시징/모니터링/DB/캐시
```

ASG를 먼저 0으로 만들고, 실제 active instance가 없어질 때까지 기다린 뒤 나머지 리소스를 내린다.

### Redis는 왜 stop이 아니라 destroy인가

RDS는 stop/start가 가능하다.

하지만 ElastiCache Redis/Valkey는 RDS처럼 단순 stop/start 방식으로 비용을 줄이기 어렵다. 그래서 Redis는 Terraform targeted destroy/apply로 처리했다.

종료 시에는 다음 리소스를 targeted destroy 대상에 포함했다.

```text
aws_elasticache_replication_group.redis
aws_ssm_parameter.redis_cluster_nodes
aws_ssm_parameter.redis_exporter_addr
```

Ansible에서는 이 target 목록을 변수로 관리하고, Terraform 명령을 `argv` 기반으로 실행했다. shell 문자열을 길게 조립하는 대신 리스트 형태로 넘기면 공백이나 quoting 문제를 줄일 수 있다.

```yaml
vars:
  redis_terraform_target_args: "{{ redis.terraform_targets | map('regex_replace', '^', '-target=') | list }}"

tasks:
  - name: Destroy Redis replication group and related SSM parameters
    ansible.builtin.command:
      argv: "{{ ['terraform', '-chdir=' + terraform_dir, 'destroy', '-auto-approve'] + redis_terraform_target_args }}"
```

SSM Parameter는 Redis endpoint와 연결되어 있다.

Redis를 삭제하면 endpoint도 더 이상 유효하지 않기 때문에 관련 SSM Parameter도 명시적으로 제거 대상에 포함했다. 의존성 때문에 함께 정리될 수 있더라도, 운영 자동화에서는 어떤 값을 함께 다루는지 명확히 드러내는 것이 더 안전하다.

## env-up: 테스트 환경 시작 자동화

`env-up.yml`은 테스트 환경을 다시 올리는 playbook이다.

핵심 흐름은 다음과 같다.

```text
1. AWS caller identity 확인
2. RDS start 요청
3. Redis Terraform targeted apply 실행
4. Redis available 상태 대기
5. Redis 관련 SSM Parameter 생성 확인
6. RDS available 상태 대기
7. Kafka EC2 3대 시작
8. terraform-mcp EC2 시작
9. terraform-mcp SSM Agent online 대기
10. redis-exporter 재기동
11. ASG desired/min/max 복구
12. 최종 요약 출력
```

시작할 때는 종료와 반대로 의존 리소스를 먼저 준비하고 애플리케이션을 마지막에 올린다.

```text
시작:
DB/캐시/메시징/모니터링 → 애플리케이션 서버
```

ASG를 마지막에 복구하는 이유는 명확하다.

애플리케이션은 RDS, Redis, Kafka가 준비된 뒤 올라와야 한다. 그렇지 않으면 부팅 직후 connection error가 발생하거나, health check가 실패할 수 있다.

## Redis exporter 재기동까지 자동화한 이유

이 프로젝트에서는 Redis exporter를 각 애플리케이션 서버에 띄우지 않고 `terraform-mcp` 인스턴스에만 띄운다.

이유는 Grafana 지표 중복 수집을 피하기 위해서다.

같은 Redis Cluster를 여러 exporter가 동시에 바라보면 Prometheus가 동일 성격의 metric을 여러 번 수집할 수 있고, Grafana에서 값이 두 배로 보이는 문제가 생길 수 있다.

그래서 Redis exporter는 중앙에서 한 번만 실행하는 구조로 두었다.

문제는 Redis를 targeted destroy/apply로 재생성하면 endpoint가 바뀔 수 있다는 점이다. 따라서 env-up 과정에서 다음 작업이 필요하다.

```text
1. Redis 재생성
2. SSM Parameter에 새 Redis endpoint 저장
3. terraform-mcp 인스턴스 시작
4. SSM으로 terraform-mcp에 원격 명령 실행
5. 기존 redis-exporter 컨테이너 제거
6. 새 endpoint를 읽어 redis-exporter 재실행
```

이전에는 이 부분을 수동으로 처리했다.

이제는 `restart-redis-exporter.yml`을 env-up 흐름에 포함해 자동으로 처리한다.

## SSM을 사용한 원격 명령 실행

terraform-mcp 인스턴스에 직접 SSH로 접속하지 않고 SSM을 사용했다.

이 방식의 장점은 다음과 같다.

- SSH key 관리 부담이 줄어든다.
- 보안 그룹에서 SSH 포트를 열 필요가 없다.
- AWS IAM 권한 기반으로 원격 명령을 실행할 수 있다.
- Ansible이 로컬에서 AWS CLI로 SSM command를 보낼 수 있다.

redis-exporter 재기동은 개념적으로 다음과 같은 흐름이다.

```text
localhost Ansible
  → aws ssm send-command
  → terraform-mcp EC2
  → docker rm -f redis-exporter
  → SSM Parameter에서 Redis endpoint 조회
  → docker run redis-exporter
```

playbook에서는 먼저 terraform-mcp의 SSM Agent가 `Online`인지 확인한 뒤, `AWS-RunShellScript` 문서로 Docker 명령을 전달했다. Redis endpoint는 playbook에 직접 박아두지 않고, terraform-mcp 안에서 SSM Parameter를 읽어 사용한다.

```yaml
- name: Ensure terraform-mcp SSM agent is online
  ansible.builtin.assert:
    that:
      - terraform_mcp_ssm_status.PingStatus == "Online"

- name: Send redis-exporter restart command through SSM
  ansible.builtin.command:
    argv: "{{ ['aws'] + aws_cli_profile_args + ['ssm', 'send-command', '--region', aws_region, '--instance-ids', terraform_mcp_instance.InstanceId, '--document-name', 'AWS-RunShellScript', '--comment', 'Restart redis-exporter from Ansible', '--parameters', redis_exporter_ssm_parameters, '--query', 'Command.CommandId', '--output', 'text'] }}"
```

SSM Parameter를 읽을 수 있는 이유는 terraform-mcp 인스턴스에 연결된 IAM Role이 해당 Parameter에 접근할 권한을 가지고 있기 때문이다.

즉, 비밀값이나 환경 설정을 playbook에 직접 박아두지 않고, AWS SSM Parameter Store를 통해 런타임에 읽는 구조다.

## RDS와 Redis lifecycle 병렬화

초기 env-up은 다음처럼 순차 실행에 가까웠다.

```text
RDS 시작 요청
→ RDS available 대기
→ Redis Terraform apply
→ Redis available 대기
→ 다음 작업
```

하지만 실제로 RDS start와 Redis apply는 서로 직접 의존하지 않는다.

둘 다 오래 걸리는 작업인데 순서대로 기다리면 전체 실행 시간이 불필요하게 길어진다.

그래서 흐름을 다음처럼 바꾸었다.

```text
RDS 시작 요청
→ Redis Terraform apply
→ Redis available 확인
→ RDS available 확인
→ 다음 작업
```

구현도 이 의도를 그대로 반영했다. RDS start API 호출은 "시작 요청"을 보낸 뒤 반환되므로, 바로 Redis Terraform apply를 진행할 수 있다. 그리고 Redis 확인이 끝난 뒤 RDS가 최종적으로 `available`이 되었는지 기다린다.

```yaml
- name: Start RDS instance
  ansible.builtin.command:
    argv: "{{ ['aws'] + aws_cli_profile_args + ['rds', 'start-db-instance', '--region', aws_region, '--db-instance-identifier', rds.db_instance_identifier] }}"
  when: rds_status_before_start == "stopped"

- name: Apply Redis replication group and related SSM parameters
  ansible.builtin.command:
    argv: "{{ ['terraform', '-chdir=' + terraform_dir, 'apply', '-auto-approve'] + redis_terraform_target_args }}"

- name: Wait until RDS instance is available
  ansible.builtin.command:
    argv: "{{ ['aws'] + aws_cli_profile_args + ['rds', 'describe-db-instances', '--region', aws_region, '--db-instance-identifier', rds.db_instance_identifier, '--query', 'DBInstances[0].DBInstanceStatus', '--output', 'text'] }}"
  register: rds_wait_cmd
  until: rds_wait_cmd.stdout | trim == "available"
  retries: 60
  delay: 30
```

env-down도 마찬가지다.

```text
RDS stop 요청
→ Redis Terraform destroy
→ RDS stopped 확인
```

이것은 모든 작업을 무작정 병렬화한 것이 아니다.

병렬화 대상은 다음 조건을 만족하는 작업으로 제한했다.

- 실제로 오래 걸리는 작업
- 서로 직접 의존하지 않는 작업
- 실패해도 원인 추적이 가능한 작업
- 순서를 바꿔도 운영 안정성이 깨지지 않는 작업

그래서 RDS와 Redis lifecycle만 겹치게 만들고, Kafka, terraform-mcp, redis-exporter, ASG는 기존 순서를 유지했다.

특히 ASG는 여전히 env-down에서 가장 먼저 내려가고, env-up에서 가장 마지막에 올라간다.

이 순서는 애플리케이션 안정성과 관련된 부분이므로 단순 시간 단축을 위해 바꾸지 않았다.

## Makefile로 실행 경험 단순화

Ansible 명령은 그대로 실행하면 길다.

예를 들어 env-down은 다음처럼 실행할 수 있다.

```bash
ANSIBLE_CONFIG=./ansible.cfg ansible-playbook -i inventory/localhost.yml playbooks/env-down.yml -e confirm_env_down=true
```

매번 이 명령을 치는 것은 번거롭다.

그래서 `ops/Makefile`에 자주 쓰는 명령을 정리했다.

```bash
cd ops

make env-status
make env-down
make env-up
```

Makefile은 새로운 자동화 도구라기보다, 사람이 반복해서 치는 명령을 짧고 일관되게 만드는 진입점이다.

실제 자동화 로직은 Ansible playbook에 있고, Makefile은 실행 경험을 단순화한다.

## 실제 검증

먼저 `env-status`로 현재 상태를 확인했다.

이때 AWS 계정, region, ASG, EC2, RDS, Redis, SSM Parameter, terraform-mcp SSM 상태가 모두 정상적으로 출력되는 것을 확인했다.

이후 `env-down`을 실행했다.

실행 중 다음과 같은 retry 메시지가 보였다.

```text
FAILED - RETRYING: Wait until app ASG has no active instances
FAILED - RETRYING: Wait until RDS instance is stopped
```

처음에는 실패처럼 보일 수 있지만, Ansible의 `until` 재시도 과정에서 나오는 정상적인 메시지다.

즉, 아직 원하는 상태가 아니기 때문에 실패로 확정하지 않고 일정 간격으로 다시 확인하는 것이다.

최종 결과는 성공이었다.

```text
PLAY RECAP
localhost : ok=29 changed=5 unreachable=0 failed=0 skipped=0 rescued=0 ignored=0
```

AWS 콘솔에서도 ASG, EC2, RDS, Redis 상태가 기대한 대로 바뀌는 것을 확인했다.

이후 `env-up`도 실행해 Redis 재생성, RDS 시작, Kafka 시작, terraform-mcp 시작, redis-exporter 재기동, ASG 복구가 정상적으로 수행되는 것을 확인했다.

## 이번 작업으로 얻은 것

이번 작업의 가장 큰 효과는 프로젝트 운영 부담을 줄인 것이다.

기존에는 비용을 아끼기 위해 인프라를 꺼두는 것은 좋았지만, 다시 켜는 과정이 번거로워서 프로젝트를 이어서 테스트하는 일이 부담스러웠다.

이제는 다음 명령만 기억하면 된다.

```bash
cd ops
make env-down
make env-up
```

자동화의 효과는 단순히 명령어 수가 줄어든 것만이 아니다.

운영 절차가 코드로 남았고, 순서가 표준화되었고, 상태 확인과 대기 로직이 포함되었다.

## 포트폴리오 관점에서의 의미

이 작업은 "Ansible을 써봤다"보다 조금 더 중요한 의미가 있다.

실제 운영에서 반복적으로 발생하는 불편을 발견했고, 비용 절감이라는 현실적인 제약 안에서 자동화로 해결했다.

포트폴리오에서 설명할 수 있는 포인트는 다음과 같다.

- AWS 테스트 인프라 비용 절감을 위해 운영 lifecycle 자동화
- Terraform과 Ansible의 책임 분리
- RDS start/stop 자동화
- Redis/Valkey Cluster targeted destroy/apply 자동화
- SSM Parameter 기반 동적 설정 관리
- SSM Run Command 기반 원격 exporter 재기동
- ASG scale down/up 자동화
- Ansible retry/wait 기반 상태 수렴 확인
- Makefile을 통한 개발자 경험 개선
- 병목 작업인 RDS와 Redis lifecycle 대기 시간 최적화

백엔드 프로젝트에서 단순히 API만 구현하는 것을 넘어, 배포와 운영, 비용, 모니터링까지 고려했다는 점을 보여줄 수 있다.

특히 플랫폼 엔지니어링 관점에서는 다음 흐름이 중요하다.

```text
불편한 수동 운영 절차 발견
→ 반복 가능한 runbook으로 정리
→ Ansible playbook으로 자동화
→ Makefile로 실행 경험 단순화
→ README와 블로그로 문서화
```

이 과정 자체가 플랫폼 엔지니어링의 기본적인 문제 해결 방식에 가깝다.

## 남은 과제

이번 작업으로 인프라 on/off는 자동화되었다.

다음 단계는 CodeDeploy 배포 스크립트를 Ansible playbook 기반으로 표준화하는 것이다.

현재 배포는 `appspec.yml`에서 여러 shell script를 lifecycle hook으로 직접 호출하는 구조다.

이 방식도 동작은 하지만, 배포 절차가 커질수록 다음 문제가 생길 수 있다.

- 배포 단계가 여러 shell script에 흩어진다.
- 각 단계의 의도가 명확히 드러나지 않는다.
- 실패 시 어느 단계에서 어떤 이유로 실패했는지 추적하기 어렵다.
- 서버가 늘어났을 때 동일한 배포 절차를 재사용하기 어렵다.

따라서 다음 개선 방향은 다음과 같다.

```text
Terraform:
  인프라 생성과 변경 관리

Ansible:
  인프라 운영 lifecycle 자동화
  애플리케이션 배포 절차 표준화

CodeDeploy:
  배포 트리거와 아티팩트 전달

Makefile:
  사람이 실행하는 명령의 진입점
```

이렇게 정리하면 프로젝트는 단순한 백엔드 애플리케이션을 넘어, 운영 가능한 시스템에 가까워진다.

## 정리

이번 작업은 비용 절감을 위해 수동으로 수행하던 AWS 인프라 on/off 작업을 Ansible playbook으로 표준화한 작업이다.

처음에는 단순히 귀찮은 명령어를 줄이기 위한 작업처럼 보였지만, 실제로는 운영 절차를 코드화하고, 반복 가능한 runbook을 만들고, 개발자 경험을 개선하는 작업이었다.

개인 프로젝트에서도 운영 비용은 현실적인 제약이고, 이 제약을 자동화로 다루는 경험은 백엔드와 플랫폼 엔지니어링 양쪽 모두에서 의미가 있다.

앞으로 이 구조 위에 CodeDeploy 배포 절차까지 Ansible로 정리하면, 인프라 생성, 운영 lifecycle, 애플리케이션 배포가 각각 명확한 책임을 가지는 구조로 발전시킬 수 있다.
