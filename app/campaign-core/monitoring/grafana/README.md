# Grafana 대시보드 Provisioning

이 디렉터리는 캠페인 모니터링에 사용하는 Grafana 대시보드 JSON과 provisioning 설정을 관리한다.

## 로컬 docker compose 환경

로컬 개발 환경의 `app/campaign-core/docker-compose.yml`은 아래 두 경로를 Grafana 컨테이너에 mount한다.

```text
./monitoring/grafana/provisioning -> /etc/grafana/provisioning
./monitoring/grafana/dashboards   -> /var/lib/grafana/dashboards
```

따라서 로컬 환경에서는 `provisioning/dashboards/dashboard.yml`이 `/var/lib/grafana/dashboards` 아래의 dashboard JSON을 읽는다.

## EC2 monitoring 서버 환경

실제 monitoring EC2에서는 Grafana가 별도 디렉터리에서 실행될 수 있다.

현재 확인된 운영 환경에서는 아래 경로만 Grafana 컨테이너에 mount되어 있었다.

```text
/home/ssm-user/monitoring/grafana/provisioning -> /etc/grafana/provisioning
```

이 경우 저장소를 `git pull`하고 Grafana 컨테이너를 재시작해도, 저장소 안의 `campaign.json`이 컨테이너 내부로 들어가지 않는다.

즉, 아래 파일이 최신이어도 Grafana는 자동으로 읽지 못한다.

```text
app/campaign-core/monitoring/grafana/dashboards/campaign.json
```

그래서 EC2에서는 dashboard JSON을 Grafana가 실제로 보고 있는 provisioning 경로로 복사해야 한다.

## 적용 방법

monitoring EC2에서 저장소 루트로 이동한 뒤 스크립트를 실행한다.

```bash
cd /home/ssm-user/1milion-campaign-orchestration-system
bash app/campaign-core/monitoring/grafana/apply-provisioning.sh
```

스크립트는 다음 작업을 수행한다.

```text
1. 저장소의 campaign.json을 monitoring provisioning 경로로 복사
2. dashboard provider 설정 파일 생성 또는 갱신
3. grafana 컨테이너 재시작
```

복사 대상 dashboard JSON:

```text
/home/ssm-user/monitoring/grafana/provisioning/dashboards/json/campaign.json
```

생성되는 provider 설정:

```text
/home/ssm-user/monitoring/grafana/provisioning/dashboards/dashboard.yml
```

Grafana 컨테이너 내부에서는 다음 경로로 보인다.

```text
/etc/grafana/provisioning/dashboards/json/campaign.json
```

## 환경 변수

기본값이 맞지 않는 환경에서는 아래 환경 변수를 바꿔 실행할 수 있다.

```bash
MONITORING_ROOT=/home/ssm-user/monitoring
GRAFANA_CONTAINER=grafana
GRAFANA_PROVISIONING_ROOT=/home/ssm-user/monitoring/grafana/provisioning
DASHBOARD_SRC=/path/to/campaign.json
```

예시:

```bash
GRAFANA_CONTAINER=grafana \
MONITORING_ROOT=/home/ssm-user/monitoring \
bash app/campaign-core/monitoring/grafana/apply-provisioning.sh
```

## 반영 확인

스크립트 실행 후 Grafana 컨테이너 안에서 dashboard JSON이 보이는지 확인한다.

```bash
sudo docker exec grafana sh -c 'grep -n "consumer_db_committed_total" /etc/grafana/provisioning/dashboards/json/campaign.json'
```

Grafana 화면에서 대시보드를 새로고침하면 아래 패널이 보여야 한다.

```text
Consumer 후단 처리량
Consumer DB 일시 실패율
Consumer DB commit batch size
```

패널은 보이는데 `No data`가 표시된다면 정상일 수 있다.

새 Consumer 지표는 Kafka Consumer가 실제로 메시지를 처리하고 DB commit 경로를 지나야 값이 생성된다. 따라서 부하 테스트를 한 번 실행한 뒤 확인한다.

## S3 업로드 여부

`apply-provisioning.sh`는 CodeDeploy 앱 배포 번들에 포함할 필요가 없다.

CodeDeploy의 S3 번들은 앱 서버 배포를 위한 파일을 전달한다.

```text
appspec.yml
deploy scripts
docker-compose.prod.yml
```

반면 이 스크립트는 monitoring EC2에서 Grafana dashboard를 반영하기 위한 운영 스크립트다.

따라서 현재 구조에서는 S3에 올리지 않고, 저장소에 버전 관리한 뒤 monitoring EC2에서 `git pull` 후 직접 실행한다.

완전 자동화를 하려면 별도의 방식이 필요하다.

```text
1. monitoring 서버의 docker compose까지 저장소에서 관리
2. CI/CD에서 SSM RunCommand로 monitoring EC2에 스크립트 실행
3. Grafana dashboard를 repo 경로로 직접 mount하도록 컨테이너 구성 변경
```

현재 단계에서는 `git pull -> apply-provisioning.sh 실행` 방식이 가장 단순하고 재현 가능하다.
