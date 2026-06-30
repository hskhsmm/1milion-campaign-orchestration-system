# CodeDeploy 배포 절차를 Ansible Playbook으로 표준화한 기록

## 왜 이 작업을 하게 되었나

이 프로젝트의 배포는 GitHub Actions와 CodeDeploy를 기반으로 동작한다.

GitHub Actions는 Docker 이미지를 빌드해서 ECR에 push하고, 배포에 사용할 이미지 주소를 SSM Parameter Store에 저장한 뒤, `appspec.yml`과 배포 스크립트를 S3 bundle로 묶어 CodeDeploy 배포를 생성한다.

CodeDeploy는 Auto Scaling Group에 속한 애플리케이션 EC2 인스턴스에 배포 bundle을 전달하고, `appspec.yml`에 정의된 lifecycle hook을 순서대로 실행한다.

기존 구조는 다음과 같았다.

```text
appspec.yml
  BeforeInstall     -> deploy/scripts/beforeInstall.sh
  AfterInstall      -> deploy/scripts/afterInstall.sh
  ApplicationStart  -> deploy/scripts/applicationStart.sh
  ValidateService   -> deploy/scripts/validateService.sh
```

이 구조는 지금까지 충분히 동작했다.

각 shell script는 명확한 역할을 가지고 있었다.

| lifecycle hook | 기존 script | 주요 역할 |
| --- | --- | --- |
| `BeforeInstall` | `beforeInstall.sh` | ECR login, SSM Parameter 조회, `/opt/campaign-core/.env.prod` 생성 |
| `AfterInstall` | `afterInstall.sh` | `.env.prod`에서 `ECR_IMAGE` 확인, Docker image pull |
| `ApplicationStart` | `applicationStart.sh` | compose 파일 배치, stress-test 스크립트 동기화, 기존 컨테이너 중지, 새 컨테이너 실행 |
| `ValidateService` | `validateService.sh` | `/actuator/health`가 `UP`이 될 때까지 확인 |

하지만 배포 절차가 커질수록 구조적인 한계가 보이기 시작했다.

- 배포 순서가 `appspec.yml`과 여러 shell script에 흩어진다.
- 서버 내부에서 실제로 어떤 작업이 수행되는지 한눈에 보기 어렵다.
- 단계별 검증과 실패 원인 파악이 script 로그에 의존한다.
- shell script는 작업 이름과 상태를 구조화해서 보여주기 어렵다.
- 같은 절차를 서버 수 증가나 다른 배포 대상에 재사용하기 어렵다.
- 인프라 on/off는 이미 Ansible로 정리했는데, 애플리케이션 배포만 shell 중심으로 남아 있다.

앞선 작업에서 비용 절감용 인프라 on/off를 Ansible Playbook 기반으로 정리했다.

그 다음 단계로 애플리케이션 CD 배포 절차도 같은 기준으로 정리하고 싶었다.

이번 작업의 목표는 단순히 shell script를 없애는 것이 아니었다.

핵심은 다음과 같았다.

```text
CodeDeploy:
  배포 트리거와 아티팩트 전달

Ansible:
  서버 내부 배포 절차 관리
  task 단위 로그와 실패 지점 제공

appspec.yml:
  lifecycle hook 선언
  wrapper script 호출
```

즉, CodeDeploy가 "언제 배포할 것인가"와 "어떤 bundle을 전달할 것인가"를 담당하고, 실제 서버 내부에서 "무엇을 어떤 순서로 실행할 것인가"는 Ansible Playbook이 담당하도록 역할을 분리하는 것이 목적이었다.

## 기존 배포 흐름

기존 GitHub Actions 배포 흐름은 다음과 같았다.

```text
main branch merge
  |
  v
GitHub Actions
  |
  |-- Docker image build
  |-- ECR push
  |-- SSM Parameter Store에 ECR_IMAGE 갱신
  |-- appspec.yml + deploy/ + stress-test/ zip 생성
  |-- S3 upload
  |-- CodeDeploy create-deployment
  v
CodeDeploy
  |
  |-- ASG 인스턴스에 bundle 전달
  |-- appspec.yml lifecycle hook 실행
```

`appspec.yml`은 다음처럼 lifecycle hook마다 서로 다른 script를 직접 호출하고 있었다.

```yaml
version: 0.0
os: linux
hooks:
  BeforeInstall:
    - location: deploy/scripts/beforeInstall.sh
      timeout: 300
      runas: root
  AfterInstall:
    - location: deploy/scripts/afterInstall.sh
      timeout: 600
      runas: root
  ApplicationStart:
    - location: deploy/scripts/applicationStart.sh
      timeout: 600
      runas: root
  ValidateService:
    - location: deploy/scripts/validateService.sh
      timeout: 300
      runas: root
```

이 방식은 직관적이다.

하지만 배포 절차를 더 운영 친화적으로 만들려면 각 script가 아니라 전체 배포 흐름을 하나의 playbook에서 읽을 수 있어야 했다.

## Ansible 기반 CD 구조 설계

새 구조는 다음처럼 잡았다.

```text
app/campaign-core/
  appspec.yml
  deploy/
    scripts/
      run-ansible-deploy.sh

ops/
  playbooks/
    deploy-app.yml
  group_vars/
    all.yml
```

CodeDeploy는 여전히 lifecycle hook을 실행한다.

다만 각 hook에서 서로 다른 shell script를 직접 호출하지 않고, 공통 wrapper인 `run-ansible-deploy.sh`만 호출한다.

```text
appspec.yml
  BeforeInstall     -> run-ansible-deploy.sh -> deploy-app.yml --tags before_install
  AfterInstall      -> run-ansible-deploy.sh -> deploy-app.yml --tags after_install
  ApplicationStart  -> run-ansible-deploy.sh -> deploy-app.yml --tags application_start
  ValidateService   -> run-ansible-deploy.sh -> deploy-app.yml --tags validate_service
```

이렇게 하면 CodeDeploy lifecycle의 의미는 유지하면서, 실제 서버 내부 배포 로직은 Ansible task로 모을 수 있다.

## AppSpec 변경

변경 후 `appspec.yml`은 다음처럼 단순해졌다.

```yaml
version: 0.0
os: linux
hooks:
  BeforeInstall:
    - location: deploy/scripts/run-ansible-deploy.sh
      timeout: 600
      runas: root
  AfterInstall:
    - location: deploy/scripts/run-ansible-deploy.sh
      timeout: 600
      runas: root
  ApplicationStart:
    - location: deploy/scripts/run-ansible-deploy.sh
      timeout: 600
      runas: root
  ValidateService:
    - location: deploy/scripts/run-ansible-deploy.sh
      timeout: 300
      runas: root
```

기존에는 hook마다 호출하는 script가 달랐지만, 이제는 모든 hook이 같은 wrapper를 호출한다.

처음에는 AppSpec에서 argument를 넘기는 방식도 생각할 수 있다.

```text
run-ansible-deploy.sh before_install
```

하지만 CodeDeploy lifecycle hook에서 argument 전달에 의존하는 구조는 피하고 싶었다.

대신 CodeDeploy가 script 실행 시 제공하는 `LIFECYCLE_EVENT` 환경변수를 사용했다.

wrapper는 `LIFECYCLE_EVENT` 값을 보고 Ansible tag를 결정한다.

| CodeDeploy lifecycle | Ansible tag |
| --- | --- |
| `BeforeInstall` | `before_install` |
| `AfterInstall` | `after_install` |
| `ApplicationStart` | `application_start` |
| `ValidateService` | `validate_service` |

## wrapper script 작성

새로 추가한 wrapper는 `app/campaign-core/deploy/scripts/run-ansible-deploy.sh`다.

역할은 다음과 같다.

- CodeDeploy lifecycle event를 Ansible tag로 변환한다.
- 배포 bundle 안의 `ops/playbooks/deploy-app.yml`을 찾는다.
- `ansible-playbook` 설치 여부를 확인한다.
- 앱 EC2에 Ansible이 없으면 bootstrap 설치를 시도한다.
- playbook 실행 실패 시 CodeDeploy가 실패로 인식하도록 exit code를 그대로 전달한다.
- 실행 경로와 tag를 로그로 남긴다.

핵심 흐름은 다음과 같다.

```bash
HOOK_TAG="${1:-}"

if [[ -z "${HOOK_TAG}" ]]; then
  case "${LIFECYCLE_EVENT:-}" in
    BeforeInstall)
      HOOK_TAG="before_install"
      ;;
    AfterInstall)
      HOOK_TAG="after_install"
      ;;
    ApplicationStart)
      HOOK_TAG="application_start"
      ;;
    ValidateService)
      HOOK_TAG="validate_service"
      ;;
  esac
fi
```

그리고 bundle 기준으로 `ops` 디렉터리를 찾는다.

```bash
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUNDLE_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
OPS_DIR="${BUNDLE_ROOT}/ops"
PLAYBOOK="${OPS_DIR}/playbooks/deploy-app.yml"
INVENTORY="${OPS_DIR}/inventory/localhost.yml"
```

마지막으로 Ansible을 실행한다.

```bash
cd "${OPS_DIR}"
ANSIBLE_CONFIG="${OPS_DIR}/ansible.cfg" \
  ansible-playbook \
    -i "${INVENTORY}" \
    "${PLAYBOOK}" \
    --tags "${HOOK_TAG}"
```

## Ansible bootstrap 처리

실제 CodeDeploy 검증 중 중요한 사실을 확인했다.

로컬에서는 Ansible이 설치되어 있었지만, 앱 ASG의 EC2 인스턴스에는 `ansible-playbook`이 없었다.

SSM으로 확인한 결과는 다음과 같았다.

```text
ansible-playbook: command not found
```

이 상태에서 wrapper가 바로 playbook을 실행하면 CodeDeploy는 `BeforeInstall`에서 실패한다.

배포 구조를 Ansible 기반으로 바꾸려면 앱 서버에도 Ansible 실행 환경이 필요하다.

장기적으로는 AMI나 launch template bootstrap에서 Ansible을 미리 설치하는 방식이 더 적절하다. 하지만 이번 이슈의 제외 범위에는 Terraform 인프라 리소스 구조 변경이 포함되어 있었기 때문에, launch template이나 AMI 변경은 하지 않았다.

대신 wrapper에서 최소 bootstrap 설치를 시도하도록 했다.

```bash
install_ansible() {
  echo "[run-ansible-deploy] ansible-playbook is not installed. Trying bootstrap install."

  if command -v dnf >/dev/null 2>&1; then
    dnf install -y ansible || dnf install -y ansible-core
  elif command -v yum >/dev/null 2>&1; then
    yum install -y ansible \
      || yum install -y ansible-core \
      || amazon-linux-extras install -y ansible2 \
      || python3 -m pip install ansible
  elif command -v python3 >/dev/null 2>&1; then
    python3 -m pip install ansible
  else
    echo "[run-ansible-deploy] ERROR: no supported package manager or python3 found for Ansible install" >&2
    return 1
  fi
}
```

실제 배포 로그에서도 이 bootstrap이 수행되었다.

```text
Installed:
  ansible-8.3.0-1.amzn2023.0.1.noarch
  ansible-core-2.15.3-1.amzn2023.0.11.x86_64
```

이후 같은 배포에서 `BeforeInstall` playbook이 정상 실행되었다.

## 배포 Playbook 작성

새 playbook은 `ops/playbooks/deploy-app.yml`이다.

기본 설정은 다음과 같다.

```yaml
- name: Deploy campaign-core application from CodeDeploy bundle
  hosts: localhost
  connection: local
  gather_facts: false
  become: true
```

앱 EC2 안에서 자기 자신을 대상으로 실행되므로 `hosts: localhost`, `connection: local`을 사용했다.

공통 경로와 health check 설정은 `ops/group_vars/all.yml`에 두었다.

```yaml
campaign_core_deploy:
  app_dir: /opt/campaign-core
  env_file: /opt/campaign-core/.env.prod
  compose_file: /opt/campaign-core/docker-compose.prod.yml
  log_dir: /opt/campaign-core/logs
  stress_dir: /opt/campaign-core/stress-test
  health_url: http://localhost:8080/actuator/health
  health_retries: 30
  health_delay_seconds: 2
```

Playbook은 lifecycle tag 단위로 실행된다.

| Ansible tag | 주요 작업 |
| --- | --- |
| `before_install` | 필수 명령 확인, AWS 계정 확인, ECR login, 앱/로그 디렉터리 생성, SSM Parameter 조회, `.env.prod` 생성 |
| `after_install` | `.env.prod` 읽기, `ECR_IMAGE` 검증, Docker image pull |
| `application_start` | compose 파일 배치, stress-test 동기화, 기존 컨테이너 중지, 새 컨테이너 실행, 컨테이너 상태 출력 |
| `validate_service` | actuator health check, 성공 결과 출력 |

## BeforeInstall task

`before_install` 단계에서는 배포에 필요한 기본 조건을 먼저 확인한다.

```yaml
- name: Check required deployment commands
  ansible.builtin.shell: "command -v {{ item }}"
  loop:
    - aws
    - docker
    - docker-compose
    - curl
```

이 task는 배포 실패 원인을 빠르게 드러내기 위한 것이다.

예를 들어 `docker-compose`가 없다면 이후 `ApplicationStart`에서 애매하게 실패하는 것보다, 배포 초반에 명확히 중단되는 편이 낫다.

그 다음 AWS caller identity를 확인한다.

```yaml
- name: Check AWS caller identity
  ansible.builtin.command:
    argv:
      - aws
      - sts
      - get-caller-identity
      - --query
      - Account
      - --output
      - text
```

배포 서버가 올바른 instance role과 region에서 AWS API를 호출하는지 확인하기 위함이다.

ECR login도 Ansible task로 옮겼다.

```yaml
- name: Read ECR login password
  ansible.builtin.command:
    argv:
      - aws
      - ecr
      - get-login-password
      - --region
      - "{{ aws_region }}"
  register: deploy_ecr_login_password_cmd
  changed_when: false
  no_log: true
```

비밀번호성 출력은 `no_log: true`로 숨겼다.

SSM Parameter Store에서 배포 환경변수를 읽어 `.env.prod`를 생성하는 작업도 playbook으로 옮겼다.

```yaml
deploy_ssm_parameters:
  SPRING_PROFILES_ACTIVE: "/batch-kafka/prod/SPRING_PROFILES_ACTIVE"
  SPRING_DATASOURCE_URL: "/batch-kafka/prod/SPRING_DATASOURCE_URL"
  SPRING_DATASOURCE_USERNAME: "/batch-kafka/prod/SPRING_DATASOURCE_USERNAME"
  SPRING_DATASOURCE_PASSWORD: "/batch-kafka/prod/SPRING_DATASOURCE_PASSWORD"
  SPRING_KAFKA_BOOTSTRAP_SERVERS: "/batch-kafka/prod/SPRING_KAFKA_BOOTSTRAP_SERVERS"
  SPRING_DATA_REDIS_CLUSTER_NODES: "/batch-kafka/prod/SPRING_DATA_REDIS_CLUSTER_NODES"
  SLACK_WEBHOOK_URL: "/batch-kafka/prod/SLACK_WEBHOOK_URL"
  ECR_IMAGE: "/batch-kafka/prod/ECR_IMAGE"
```

최종적으로 `/opt/campaign-core/.env.prod`를 `0600` 권한으로 생성한다.

```yaml
- name: Write deployment environment file
  ansible.builtin.copy:
    dest: "{{ deploy_env_file }}"
    owner: root
    group: root
    mode: "0600"
    content: "{{ deploy_env_lines | join('\n') }}\n"
  no_log: true
```

마지막으로 `ECR_IMAGE`가 없으면 명확히 실패시킨다.

```yaml
- name: Verify ECR_IMAGE parameter exists
  ansible.builtin.assert:
    that:
      - "'ECR_IMAGE=' in (deploy_env_lines | join('\n'))"
    fail_msg: "ECR_IMAGE is missing from {{ deploy_env_file }}"
```

## AfterInstall task

`after_install` 단계에서는 `.env.prod`에서 `ECR_IMAGE`를 읽고, Docker image를 pull한다.

```yaml
- name: Read deployment environment file
  ansible.builtin.slurp:
    src: "{{ deploy_env_file }}"
  register: deploy_env_file_slurp
```

`slurp`를 사용한 이유는 원격 파일 내용을 Ansible 변수로 다루기 위해서다.

이미지를 읽은 뒤 값이 비어 있으면 중단한다.

```yaml
- name: Verify ECR image is configured
  ansible.builtin.assert:
    that:
      - deploy_ecr_image | length > 0
    fail_msg: "ECR_IMAGE is not configured in {{ deploy_env_file }}"
```

그리고 이미지를 pull한다.

```yaml
- name: Pull application image
  ansible.builtin.command:
    argv:
      - docker
      - pull
      - "{{ deploy_ecr_image }}"
```

기존 `afterInstall.sh`의 역할을 그대로 Ansible task로 옮긴 부분이다.

## ApplicationStart task

`application_start` 단계는 실제로 서버 상태를 바꾸는 핵심 구간이다.

먼저 배포 bundle 안에 `docker-compose.prod.yml`이 있는지 확인한다.

```yaml
- name: Verify docker-compose file exists in deployment bundle
  ansible.builtin.stat:
    path: "{{ deploy_compose_src }}"
  register: deploy_compose_src_stat
```

없으면 명확한 메시지와 함께 실패한다.

```yaml
- name: Fail when docker-compose file is missing
  ansible.builtin.fail:
    msg: "docker-compose.prod.yml not found in deployment bundle: {{ deploy_compose_src }}"
  when: not deploy_compose_src_stat.stat.exists
```

이후 compose 파일을 운영 경로로 복사한다.

```yaml
- name: Copy production docker-compose file
  ansible.builtin.copy:
    src: "{{ deploy_compose_src }}"
    dest: "{{ deploy_compose_file }}"
    owner: root
    group: root
    mode: "0644"
    remote_src: true
```

부하 테스트 스크립트가 bundle에 포함되어 있으면 `/opt/campaign-core/stress-test`로 동기화한다.

```yaml
- name: Copy stress-test scripts
  ansible.builtin.copy:
    src: "{{ deploy_stress_src }}"
    dest: "{{ deploy_app_dir }}"
    owner: root
    group: root
    mode: preserve
    remote_src: true
  when: deploy_stress_src_stat.stat.exists
```

기존 컨테이너는 먼저 내린다.

```yaml
- name: Stop existing application containers
  ansible.builtin.command:
    argv:
      - docker-compose
      - -f
      - "{{ deploy_compose_file }}"
      - down
      - --remove-orphans
  failed_when: false
```

컨테이너 이름 충돌을 피하기 위해 남은 컨테이너도 정리한다.

```yaml
- name: Remove leftover application containers
  ansible.builtin.command:
    argv:
      - docker
      - rm
      - -f
      - campaign-core-app
      - redis-exporter
  failed_when: false
```

그리고 새 컨테이너를 실행한다.

```yaml
- name: Start application containers
  ansible.builtin.command:
    argv:
      - docker-compose
      - -f
      - "{{ deploy_compose_file }}"
      - --env-file
      - "{{ deploy_env_file }}"
      - up
      - -d
      - --remove-orphans
```

## 실제 배포 중 발견한 문제

첫 번째 실제 CodeDeploy 검증에서는 `ApplicationStart` 단계에서 실패했다.

배포 상태는 다음과 같았다.

```text
Deployment ID: d-NR88406FJ
Status: Failed
Overview:
  Succeeded: 0
  Failed: 1
  Skipped: 1
```

인스턴스별 lifecycle event를 확인해보니 실패 지점은 `ApplicationStart`였다.

```text
lifecycleEventName: ApplicationStart
status: Failed
scriptName: deploy/scripts/run-ansible-deploy.sh
message: exit code 2
```

CodeDeploy 로그를 SSM으로 조회했다.

```bash
aws ssm send-command \
  --region ap-northeast-2 \
  --instance-ids i-04468a92640ed1b6e \
  --document-name "AWS-RunShellScript" \
  --comment "read codedeploy failure log" \
  --parameters 'commands=[
    "sudo tail -n 300 /opt/codedeploy-agent/deployment-root/deployment-logs/codedeploy-agent-deployments.log",
    "sudo tail -n 200 /var/log/aws/codedeploy-agent/codedeploy-agent.log"
  ]'
```

로그에서 실제 실패 task가 드러났다.

```text
TASK [Print started container summary]
fatal: [localhost]: FAILED!
cmd:
  docker-compose -f /opt/campaign-core/docker-compose.prod.yml ps

stderr:
  The "ECR_IMAGE" variable is not set. Defaulting to a blank string.
  service "app" has neither an image nor a build context specified: invalid compose project
```

처음에는 컨테이너 실행 자체가 실패한 것처럼 보였다.

하지만 바로 앞 task를 보면 컨테이너 실행은 성공했다.

```text
TASK [Start application containers]
changed: [localhost]
```

실패한 것은 컨테이너 실행이 아니라, 그 다음 상태 출력용 `docker-compose ps`였다.

원인은 단순했다.

`docker-compose up`에는 `--env-file`을 넘겼다.

```bash
docker-compose -f /opt/campaign-core/docker-compose.prod.yml \
  --env-file /opt/campaign-core/.env.prod \
  up -d --remove-orphans
```

하지만 `docker-compose ps`에는 `--env-file`을 넘기지 않았다.

```bash
docker-compose -f /opt/campaign-core/docker-compose.prod.yml ps
```

compose 파일 안에서는 이미지가 `${ECR_IMAGE}`로 정의되어 있다.

따라서 `ps` 실행 시에도 env file이 필요했다.

수정은 다음과 같다.

```yaml
- name: Print started container summary
  ansible.builtin.command:
    argv:
      - docker-compose
      - -f
      - "{{ deploy_compose_file }}"
      - --env-file
      - "{{ deploy_env_file }}"
      - ps
```

이 문제는 Ansible로 전환했기 때문에 빠르게 찾을 수 있었다.

기존 shell script였다면 "ApplicationStart 실패" 정도만 보고 Docker 로그를 뒤져야 했을 수 있다. 이번에는 Ansible task 이름이 `Print started container summary`로 바로 찍혔고, 실패한 명령과 stderr가 같이 남았다.

## ValidateService task

마지막 단계는 actuator health check다.

```yaml
- name: Wait for application health endpoint
  ansible.builtin.uri:
    url: "{{ deploy_health_url }}"
    method: GET
    return_content: true
    status_code: 200
    timeout: 2
  register: deploy_health_check
  until: deploy_health_check.json.status | default('') == 'UP'
  retries: "{{ campaign_core_deploy.health_retries }}"
  delay: "{{ campaign_core_deploy.health_delay_seconds }}"
```

기존 `validateService.sh`는 `curl`과 반복문으로 health check를 수행했다.

Ansible에서는 `uri`, `until`, `retries`, `delay`를 사용해 같은 로직을 선언적으로 표현할 수 있다.

```text
최대 대기 시간:
  health_retries: 30
  health_delay_seconds: 2
  => 약 60초
```

성공 시에는 health 결과를 출력한다.

```yaml
- name: Print application health result
  ansible.builtin.debug:
    msg: "Health OK: {{ deploy_health_check.json | default(deploy_health_check.content | default('')) }}"
```

## GitHub Actions bundle 수정

중요한 변경 중 하나는 배포 bundle 구성이다.

기존 bundle에는 다음만 포함되어 있었다.

```text
appspec.yml
deploy/
stress-test/
```

하지만 이제 앱 EC2에서 `ops/playbooks/deploy-app.yml`을 실행해야 한다.

따라서 GitHub Actions에서 `ops/`도 bundle에 포함하도록 수정했다.

```yaml
- name: Zip deployment bundle
  run: |
    rm -rf bundle deploy.zip
    mkdir -p bundle
    cp appspec.yml bundle/
    cp -r deploy bundle/
    cp -r ../../ops bundle/
    cp -r ../../stress-test bundle/
    cd bundle
    zip -r ../deploy.zip \
      appspec.yml \
      deploy/ \
      ops/ \
      stress-test/
```

그리고 `ops/**` 변경 시에도 배포 workflow가 실행되도록 trigger path를 추가했다.

```yaml
paths:
  - 'app/campaign-core/**'
  - 'ops/**'
  - 'stress-test/**'
  - '.github/workflows/**'
```

이 변경이 없으면 `appspec.yml`은 wrapper를 실행하지만, 서버 안의 deployment archive에 `ops/playbooks/deploy-app.yml`이 없어 `playbook not found`로 실패한다.

## 최신 Ansible 호환성 문제

인프라를 다시 올리는 과정에서 `restart-redis-exporter.yml`도 최신 Ansible에서 한 번 실패했다.

문제는 assert 조건이었다.

```yaml
- redis_exporter_addr | regex_search('^redis://')
```

기존 Ansible에서는 문자열이 truthy 값으로 처리될 수 있었지만, 최신 Ansible은 conditional 결과가 명확한 boolean이 아니면 실패한다.

실패 메시지는 다음과 같았다.

```text
Conditional result (True) was derived from value of type 'str'
Conditionals must have a boolean result.
```

수정 후 조건은 다음과 같다.

```yaml
- redis_exporter_addr is match('^redis://')
```

이 수정은 이번 CodeDeploy 작업의 핵심은 아니지만, 실제 검증 과정에서 드러난 운영 자동화 호환성 문제였다.

Ansible을 운영 자동화의 중심으로 두면 이런 버전 차이에 의한 조건식 문제도 명확히 드러난다.

## 검증 과정

먼저 로컬에서 syntax check를 수행했다.

```bash
cd ops
ANSIBLE_CONFIG=./ansible.cfg ansible-playbook -i inventory/localhost.yml playbooks/deploy-app.yml --syntax-check
```

결과는 다음과 같았다.

```text
playbook: playbooks/deploy-app.yml
```

추가로 관련 playbook도 확인했다.

```bash
ANSIBLE_CONFIG=./ansible.cfg ansible-playbook -i inventory/localhost.yml playbooks/restart-redis-exporter.yml --syntax-check
ANSIBLE_CONFIG=./ansible.cfg ansible-playbook -i inventory/localhost.yml playbooks/env-up.yml --syntax-check
```

둘 다 syntax check를 통과했다.

```text
playbook: playbooks/restart-redis-exporter.yml
playbook: playbooks/env-up.yml
```

그 다음 실제 배포 전 인프라를 올렸다.

```bash
make env-up
```

결과는 성공이었다.

```text
PLAY RECAP
localhost : ok=56 changed=3 unreachable=0 failed=0 skipped=3 rescued=0 ignored=0
```

상태 확인도 통과했다.

```bash
make env-status
```

```text
PLAY RECAP
localhost : ok=16 changed=0 unreachable=0 failed=0 skipped=0 rescued=0 ignored=0
```

이때 확인된 상태는 다음과 같았다.

| 리소스 | 상태 |
| --- | --- |
| RDS | `available` |
| Redis / Valkey | `available` |
| Redis 관련 SSM Parameter | 재생성 완료 |
| Kafka broker EC2 | `running` |
| terraform-mcp | `running` |
| App ASG | 인스턴스 2대 `InService`, `Healthy` |

App ASG 인스턴스 상태도 확인했다.

```text
Health   Instance ID            Lifecycle
Healthy  i-000c9f3513d9a2f18    InService
Healthy  i-04468a92640ed1b6e    InService
```

## 실제 CodeDeploy 검증

첫 번째 배포는 앞서 설명한 `docker-compose ps` env file 문제로 실패했다.

수정 후 다시 배포를 실행했다.

최종 성공한 배포는 다음과 같다.

```text
Deployment ID: d-1FSX756FJ
CreateTime:   2026-07-01T02:13:31+09:00
CompleteTime: 2026-07-01T02:17:18+09:00
Status:       Succeeded
```

CodeDeploy overview는 다음과 같았다.

```json
{
  "Pending": 0,
  "InProgress": 0,
  "Succeeded": 2,
  "Failed": 0,
  "Skipped": 0,
  "Ready": 0
}
```

즉 ASG 인스턴스 2대 모두 배포에 성공했다.

마지막으로 ALB를 통해 health check를 확인했다.

```bash
curl -fsS http://alb-batch-kafka-api-1351817547.ap-northeast-2.elb.amazonaws.com/actuator/health
```

결과는 다음과 같았다.

```json
{"groups":["liveness","readiness"],"status":"UP"}
```

이로써 다음 조건을 모두 확인했다.

| 검증 항목 | 결과 |
| --- | --- |
| CodeDeploy 배포 생성 | 성공 |
| `BeforeInstall` Ansible 실행 | 성공 |
| `AfterInstall` Ansible 실행 | 성공 |
| `ApplicationStart` Ansible 실행 | 성공 |
| `ValidateService` health check | 성공 |
| ASG 인스턴스 2대 배포 | 성공 |
| ALB 경유 actuator health | `UP` |

## 이번 작업으로 바뀐 점

기존에는 배포 절차가 여러 shell script에 나뉘어 있었다.

```text
beforeInstall.sh
afterInstall.sh
applicationStart.sh
validateService.sh
```

이제 실제 배포 절차는 `ops/playbooks/deploy-app.yml`에서 task 단위로 관리된다.

```text
deploy-app.yml
  before_install
  after_install
  application_start
  validate_service
```

`appspec.yml`은 lifecycle hook을 선언하고 wrapper를 호출하는 역할로 단순해졌다.

이 구조의 장점은 다음과 같다.

- 배포 절차를 하나의 playbook에서 읽을 수 있다.
- 각 단계의 의도가 task name으로 드러난다.
- 실패 지점이 Ansible task 단위로 남는다.
- 기존 shell script보다 상태 확인과 재시도 로직을 표현하기 쉽다.
- 인프라 on/off 자동화와 애플리케이션 배포 자동화가 같은 도구 체계로 정리된다.

## 아쉬운 점과 다음 개선 방향

이번 작업에서 Ansible bootstrap을 wrapper에 넣었다.

이는 현실적인 보완이지만, 가장 이상적인 방식은 아니다.

배포 시점에 패키지를 설치하면 다음 문제가 생길 수 있다.

- 첫 배포 시간이 길어진다.
- 외부 패키지 저장소 상태에 영향을 받는다.
- 동일 AMI라도 배포 시점에 따라 설치되는 Ansible 버전이 달라질 수 있다.

따라서 다음 개선 방향은 앱 AMI 또는 launch template bootstrap에서 Ansible을 미리 설치하는 것이다.

예상 방향은 다음과 같다.

```text
AMI bake 단계:
  Docker
  CodeDeploy agent
  AWS CLI
  docker-compose
  Ansible

CodeDeploy 시점:
  이미 준비된 Ansible로 deploy-app.yml 실행
```

또한 기존 shell script는 아직 삭제하지 않았다.

이번 변경이 실제 배포에서 안정적으로 동작하는 것을 확인한 뒤, 별도 작업으로 제거하거나 archive 처리하는 편이 안전하다.

## 정리

이번 작업은 CodeDeploy 배포 절차를 Ansible Playbook 기반으로 표준화한 작업이다.

단순히 shell script를 Ansible로 옮긴 것이 아니라, 배포 책임을 다음처럼 재정리했다.

```text
GitHub Actions:
  이미지 빌드, ECR push, SSM ECR_IMAGE 갱신, S3 bundle 업로드, CodeDeploy 트리거

CodeDeploy:
  ASG 인스턴스 대상 배포 orchestration
  lifecycle hook 실행

Ansible:
  서버 내부 배포 절차
  task 단위 검증
  실패 지점 추적
```

이전 작업에서 비용 절감용 인프라 on/off를 Ansible로 표준화했고, 이번에는 애플리케이션 배포 절차까지 같은 방식으로 정리했다.

그 결과 프로젝트의 운영 자동화 구조는 다음처럼 정리된다.

```text
Terraform:
  인프라 리소스 정의

Ansible:
  인프라 운영 lifecycle
  애플리케이션 배포 lifecycle

CodeDeploy:
  배포 트리거와 아티팩트 전달

GitHub Actions:
  CI/CD 진입점
```

개인 프로젝트라도 실제 운영 환경에 가까운 시스템을 만들려면, 애플리케이션 코드뿐 아니라 배포와 운영 절차도 함께 설계해야 한다.

이번 작업은 그 배포 절차를 shell script 중심에서 task 중심의 선언적 자동화로 옮긴 과정이었다.
