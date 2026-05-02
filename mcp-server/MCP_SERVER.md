# MCP Monitor Server — 상세 설명서

## 목차

1. [이게 뭔가 / 왜 만들었나](#1-이게-뭔가--왜-만들었나)
2. [전체 파일 구조](#2-전체-파일-구조)
3. [파일별 상세 설명](#3-파일별-상세-설명)
4. [전체 동작 메커니즘](#4-전체-동작-메커니즘)
5. [인프라 적용 방법](#5-인프라-적용-방법)
6. [Claude (AI) 연동](#6-claude-ai-연동)
7. [생각보다 금방 끝난 이유](#7-생각보다-금방-끝난-이유)

---

## 1. 이게 뭔가 / 왜 만들었나

### 한 줄 요약

> Prometheus / CloudWatch를 30초마다 폴링해서 이상 감지하면 Slack으로 알림 보내는 AI 운영 서버.
> Claude가 도구를 직접 호출해서 운영 상황을 묻고 분석할 수 있게 MCP 프로토콜로 도구를 노출한다.

### 배경

100만 트래픽 캠페인 시스템(Phase B)을 구축했는데, 운영하면서 사람이 Grafana를 24시간 보고 있을 수는 없다.
Phase E 목표: **AI가 이상을 탐지하고 설명한다. 실행(인프라 변경)은 사람이 한다.**

AWS 쓰기 권한 없음 — 읽기 전용으로만 동작한다.

### 감지 범위

| 우선순위 | 항목 | 임계값 |
|----------|------|--------|
| P1 (즉시 대응) | 5xx 에러 발생 | 1건 이상 |
| P1 | Redis Queue CRITICAL | 850,000건 (85%) |
| P1 | 데이터 정합성 불일치 | Redis ≠ DB |
| P2 (성능 경고) | 앱 CPU CRITICAL | 90% |
| P2 | 앱 CPU WARNING | 80% |
| P2 | Kafka consumer lag CRITICAL | 1,000 |
| P2 | Kafka consumer lag WARNING | 500 |
| P2 | Consumer 처리 지연 CRITICAL | 200ms |
| P2 | Consumer 처리 지연 WARNING | 50ms |
| P2 | HikariCP 커넥션 대기 | 1개 이상 |
| P3 (인프라 모니터링) | RDS CPU WARNING | 60% |
| P3 | Bridge 드레인 사이클 지연 | 60초 초과 |

---

## 2. 전체 파일 구조

```
mcp-server/
├── main.py                # FastAPI 앱 진입점 + APScheduler 등록 + MCP 마운트
├── monitor.py             # 폴링 루프 — 각 detector 호출 조율
├── config.py              # 임계값 + 환경변수 단일 관리 포인트
├── state.py               # 중복 알림 방지 cooldown 관리 (인메모리)
├── slack.py               # Slack Webhook 전송 + 메시지 포맷
├── tools.py               # MCP 도구 5개 노출 (Claude용)
├── requirements.txt       # Python 의존성
├── Dockerfile
└── detectors/
    ├── __init__.py
    ├── p1_detector.py     # 5xx 에러 / Redis Queue / 데이터 정합성
    ├── p2_detector.py     # 앱 CPU / Kafka lag / Consumer 지연 / HikariCP
    └── p3_detector.py     # RDS CPU / Bridge 드레인 사이클
```

---

## 3. 파일별 상세 설명

---

### `config.py` — 설정 단일 관리 포인트

모든 임계값과 환경변수를 **이 파일에서만** 관리한다.
detector 파일들이 각자 숫자를 하드코딩하지 않고 `import config` 하나로 참조한다.
임계값을 바꾸고 싶으면 이 파일만 수정하면 된다.

```python
SLACK_WEBHOOK_URL = os.environ["SLACK_WEBHOOK_URL"]  # 필수 — 없으면 기동 즉시 에러
PROMETHEUS_URL    = os.environ.get("PROMETHEUS_URL", "http://prometheus:9090")
AWS_REGION        = os.environ.get("AWS_REGION", "ap-northeast-2")
ASG_NAME          = os.environ.get("ASG_NAME", "batch-kafka-app-asg")
RDS_ID            = os.environ.get("RDS_ID", "batch-kafka-db")
BATCH_API_URL     = os.environ.get("BATCH_API_URL", "http://alb-...")
BATCH_CAMPAIGN_ID = int(os.environ.get("BATCH_CAMPAIGN_ID", "0"))
```

`SLACK_WEBHOOK_URL`만 필수값.
나머지는 기본값이 있어서 환경변수 주입 없이도 로컬 테스트 가능.

---

### `state.py` — 중복 알림 방지

30초마다 폴링하는데 CPU가 10분 동안 90%면 Slack 알림이 20번 울린다.
이걸 막는 역할. 쿨다운(기본 5분) 동안은 같은 알림을 보내지 않는다.

```python
_alert_state: dict[str, float] = {}  # {"cpu_critical": 1714900000.0}

can_alert(key, cooldown)   # 쿨다운이 지났으면 True → 알림 보내도 됨
record_alert(key)          # 알림 보낸 시각 기록 → 쿨다운 시작
reset_alert(key)           # 이상 상태 해소 시 상태 삭제 → 다음 이상 시 즉시 알림
```

**쿨다운 동작 흐름**

```
이상 감지 → can_alert() True → 알림 발송 → record_alert() → 5분 쿨다운 시작
30초 후   → can_alert() False (4분 30초 남음) → 스킵
5분 후    → can_alert() True → 다시 알림
이상 해소 → reset_alert() → 상태 초기화 → 다음 이상 시 즉시 알림 가능
```

인메모리 dict라서 컨테이너 재시작 시 초기화된다.
이건 의도적 설계 — 재시작 후 이전 상태 오염 없이 깨끗하게 시작.

---

### `slack.py` — Slack 메시지 전송

모든 detector가 공통으로 쓰는 Slack 전송 창구.
`send_alert(level, title, body)` 하나만 노출.

```python
LEVEL_EMOJI = {
    "P1": ":red_circle:",          # 🔴
    "P2": ":large_yellow_circle:", # 🟡
    "P3": ":large_blue_circle:",   # 🔵
    "OK": ":white_check_mark:",    # ✅
}
```

실제 Slack에 오는 메시지 예시:
```
🔴 *[P1] Redis Queue CRITICAL*
Queue 적재량 870,000 (임계값 850,000 / 85%)
데이터 유실 위험 — MAX_QUEUE_SIZE 초과 임박
```

`try/except`로 전송 실패를 삼킨다.
Slack이 다운돼도 모니터링 서버가 죽으면 안 되기 때문.

---

### `monitor.py` — 폴링 루프 조율자

`main.py`에서 스케줄러가 직접 부르는 함수 2개를 제공한다.

```python
def run_monitor() -> None:          # 30초마다
    for check in [check_p1, check_p2, check_p3]:
        try:
            check()
        except Exception as e:
            logger.error(...)       # 에러 로그만, 다음 detector는 계속 실행

def run_consistency_check() -> None:  # 1시간마다
    check_consistency()
```

**핵심 설계**: 각 detector를 개별 `try/except`로 감쌈.
p1이 Prometheus 연결 실패로 터져도 p2, p3는 정상 실행된다.

---

### `detectors/p1_detector.py` — P1 (가장 심각)

#### 5xx 에러 감지

```python
promql = 'sum(increase(http_server_requests_seconds_count{status=~"5.."}[30s]))'
```

`increase(...[30s])`는 Prometheus가 **최근 30초 증가량을 직접 계산해서 반환**한다.
별도 delta 계산 없이 이 값 자체를 사용.
1건이라도 발생하면 P1 알림 (`HTTP_5XX_THRESHOLD=0`).

#### Redis Queue 적재량 감지

```python
promql = "campaign_redis_queue_size"
```

2단계 임계값:
- `>= 700,000` (70%) → P2 WARNING "Consumer 확인 권장"
- `>= 850,000` (85%) → P1 CRITICAL "데이터 유실 위험"

CRITICAL 발생 시 WARNING 쿨다운은 리셋 — CRITICAL 알림이 오면 WARNING 중복 방지.

#### 데이터 정합성 검사 (1시간 주기)

```python
GET /api/admin/campaigns/{id}/consistency
→ {"redisCount": 1000000, "dbCount": 1000000}
```

Redis 확정 건수 vs DB INSERT 건수 비교.
차이 > 0 이면 P1 (유실 또는 중복 의심).
일치하면 OK 알림 (1시간마다 "정상 확인" 메시지).

`BATCH_CAMPAIGN_ID=0`이면 검사 스킵 — 환경변수 미설정 안전 처리.

---

### `detectors/p2_detector.py` — P2 (성능 경고)

#### 앱 CPU (CloudWatch)

Prometheus가 아닌 **CloudWatch** 사용 이유:
Prometheus는 앱 자체 JVM 메트릭만 노출한다.
EC2 인스턴스 레벨 CPU는 CloudWatch ASG 디멘션으로 조회해야 정확하다.

```python
Namespace="AWS/EC2"
Dimensions=[{"Name": "AutoScalingGroupName", "Value": "batch-kafka-app-asg"}]
Statistics=["Average"]
```

최근 2분 데이터포인트 중 **최고값** 기준 (최근 1분이 CloudWatch 집계 지연으로 비어있을 수 있음).

#### Kafka lag

```python
promql = "sum(kafka_consumergroup_lag_sum)"
```

파티션 10개 × Consumer group 전체 합산.
11차 테스트 때 rebalancing 순간 700 스파이크 경험 → WARNING=500, CRITICAL=1,000으로 설정.

#### Consumer 처리 지연

```python
promql = "campaign_consumer_latency_ms"
```

11차 테스트 정상 범위: 7.5~15ms.
50ms부터 이상 신호, 200ms는 DB 병목 또는 rebalancing 의심.

#### HikariCP pending

```python
promql = "max(hikaricp_pending_threads)"
```

`avg()`가 아닌 `max()` — ASG 2대 중 **한 대라도** pending 있으면 알림.
avg()면 한 대가 100이어도 다른 한 대 0이면 50으로 희석된다.

---

### `detectors/p3_detector.py` — P3 (인프라 모니터링)

#### RDS CPU (CloudWatch)

```python
Namespace="AWS/RDS"
Dimensions=[{"Name": "DBInstanceIdentifier", "Value": "batch-kafka-db"}]
```

11차 테스트 최대치 47% → **60%부터 이상**으로 판단.
알림 메시지에 "11차 테스트 최대치 47%" 기준을 명시 — 운영자가 맥락 없이도 판단 가능.

#### Bridge 드레인 사이클

```python
promql = "campaign_bridge_cycle_seconds"
```

`ParticipationBridge`는 `@Scheduled(fixedDelay=100ms)`.
정상이면 사이클이 수백ms 이내.
60초 초과 → Bridge가 멈췄거나 Kafka 연결 문제로 블로킹된 것.
알림에 Redis Queue 적재량 연계 확인 힌트 포함.

---

### `tools.py` — MCP 도구 5개

Claude가 SSE로 연결해서 직접 호출할 수 있는 도구들.
**이 파일이 "MCP 서버"의 핵심 — Claude가 운영 상황을 직접 물어볼 수 있는 인터페이스.**

| 도구 | 하는 일 | 예시 사용 상황 |
|------|---------|--------------|
| `get_monitor_status` | 현재 cooldown 상태 조회 | "지금 어떤 알림이 쿨다운 중이야?" |
| `run_check` | p1/p2/p3/all 수동 즉시 실행 | "지금 당장 P1 검사 돌려봐" |
| `query_prometheus` | PromQL 현재 값 조회 | "Redis Queue 지금 몇 건이야?" |
| `query_prometheus_range` | 특정 구간 메트릭 시계열 조회 | "40분 전~20분 전 Kafka lag 봐줘" |
| `get_test_report` | 테스트 구간 전체 핵심 메트릭 요약 | "방금 끝난 테스트 어디서 문제였어?" |
| `reset_cooldown` | 특정 alert 쿨다운 리셋 | "cpu_critical 쿨다운 풀어줘" |
| `trigger_consistency_check` | 정합성 검사 즉시 트리거 | "지금 정합성 검사 돌려봐" |

MCP 엔드포인트: `GET /mcp/sse` (Claude 연결점)

---

### `main.py` — 진입점

```python
@asynccontextmanager
async def lifespan(app: FastAPI):
    scheduler.add_job(run_monitor, "interval", seconds=30, id="monitor")
    scheduler.add_job(run_consistency_check, "interval", hours=1, id="consistency")
    scheduler.start()
    send_alert("OK", "모니터링 시작", "MCP 모니터링 서버가 정상 기동되었습니다.")
    yield                          # ← 여기서 앱 실행 중
    scheduler.shutdown(wait=False) # ← 종료 시 실행

app = FastAPI(title="MCP Monitor Server", routes=mcp_routes, lifespan=lifespan)
```

FastAPI가 HTTP 서버 역할, APScheduler가 백그라운드 스케줄러 역할.
둘이 같은 프로세스 안에서 동작.
`@app.on_event`는 FastAPI 0.93+에서 deprecated — `lifespan` 컨텍스트 매니저 패턴 사용.

`GET /health` → `{"status": "ok"}` — Docker HEALTHCHECK용.

---

### `Dockerfile`

```dockerfile
FROM python:3.11-slim          # 가벼운 베이스 이미지
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt   # 의존성 레이어 캐싱
COPY . .
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health')" || exit 1
CMD ["python", "main.py"]
```

HEALTHCHECK가 `/health`를 30초마다 찌름. 3번 실패 시 컨테이너 `unhealthy` 상태 표시.
`python:3.11-slim`에는 curl이 없어서 표준 라이브러리 `urllib.request`로 대체.

---

## 4. 전체 동작 메커니즘

```
docker run 실행
        │
        ▼
    main.py 기동
        │
        ├── FastAPI 앱 시작 (port 8000)
        │       ├── GET /health       ← Docker HEALTHCHECK
        │       └── GET /mcp/sse      ← Claude 연결 진입점
        │
        ├── APScheduler 시작
        │       │
        │       ├── [30초마다] run_monitor()
        │       │       │
        │       │       ├── check_p1()
        │       │       │     ├── Prometheus 쿼리 (5xx 건수)
        │       │       │     └── Prometheus 쿼리 (Redis Queue 크기)
        │       │       │
        │       │       ├── check_p2()
        │       │       │     ├── CloudWatch 조회 (ASG CPU)
        │       │       │     ├── Prometheus 쿼리 (Kafka lag)
        │       │       │     ├── Prometheus 쿼리 (Consumer 지연)
        │       │       │     └── Prometheus 쿼리 (HikariCP pending)
        │       │       │
        │       │       └── check_p3()
        │       │             ├── CloudWatch 조회 (RDS CPU)
        │       │             └── Prometheus 쿼리 (Bridge 사이클)
        │       │
        │       └── [1시간마다] run_consistency_check()
        │               └── GET /api/admin/campaigns/{id}/consistency
        │
        └── Slack "모니터링 시작" 전송
```

**이상 감지 시 상세 흐름 예시 (CPU)**

```
[T+0s]   check_p2() 실행
         → CloudWatch: ASG CPU = 91%
         → 91% >= CPU_CRITICAL_PERCENT(90%) 조건 충족
         → can_alert("cpu_critical") == True  ← 쿨다운 없음
         → send_alert("P2", "앱 CPU CRITICAL", "ASG CPU 91%...")
         → record_alert("cpu_critical")  ← 5분 쿨다운 시작
         → Slack에 🟡 [P2] 메시지 수신

[T+30s]  check_p2() 실행
         → CloudWatch: ASG CPU = 93%
         → can_alert("cpu_critical") == False  ← 쿨다운 중 (4분 30초 남음)
         → 스킵 (Slack 발송 없음)

[T+300s] check_p2() 실행
         → CloudWatch: ASG CPU = 88%
         → 88% < CPU_CRITICAL_PERCENT(90%)
         → 88% >= CPU_WARNING_PERCENT(80%)
         → can_alert("cpu_warning") == True
         → send_alert("P2", "앱 CPU WARNING", "ASG CPU 88%...")
         → record_alert("cpu_warning")
         → reset_alert("cpu_critical")  ← CRITICAL 상태 초기화

[T+600s] check_p2() 실행
         → CloudWatch: ASG CPU = 72%
         → 72% < CPU_WARNING_PERCENT(80%)
         → reset_alert("cpu_critical"), reset_alert("cpu_warning")
         → 정상 (Slack 발송 없음)
```

---

## 5. 인프라 적용 방법

### 실행 환경

`terraform-mcp` EC2 인스턴스에서 Docker 컨테이너로 실행.
기존 `monitoring` Docker 네트워크에 합류 — Prometheus, redis-exporter와 컨테이너 이름 기반 통신.

```
monitoring 네트워크
├── prometheus       ← mcp-monitor가 http://prometheus:9090 으로 접근
├── redis-exporter
├── kafka-exporter
└── mcp-monitor      ← 신규 추가
```

### 빌드 및 실행

```bash
# terraform-mcp EC2에서 실행

# 1. 레포 클론 (또는 pull)
cd ~/1milion-campaign-orchestration-system

# 2. 이미지 빌드
sudo docker build -t mcp-monitor ./mcp-server

# 3. 컨테이너 실행
sudo docker run -d \
  --name mcp-monitor \
  --restart unless-stopped \
  --network monitoring \
  -p 8000:8000 \
  -e SLACK_WEBHOOK_URL="https://hooks.slack.com/services/xxx/yyy/zzz" \
  -e PROMETHEUS_URL="http://prometheus:9090" \
  -e AWS_REGION="ap-northeast-2" \
  -e ASG_NAME="batch-kafka-app-asg" \
  -e RDS_ID="batch-kafka-db" \
  -e BATCH_API_URL="http://alb-batch-kafka-api-1351817547.ap-northeast-2.elb.amazonaws.com" \
  -e BATCH_CAMPAIGN_ID="1" \
  mcp-monitor

# 4. monitoring 네트워크 연결 (run 시 --network로 이미 연결됨, 확인용)
sudo docker network inspect monitoring | grep mcp-monitor

# 5. 기동 확인
curl http://localhost:8000/health
# → {"status": "ok"}

# 6. 로그 확인
sudo docker logs mcp-monitor --follow
```

### AWS IAM 권한 설정

CloudWatch 조회(ASG CPU, RDS CPU)를 위해 `terraform-mcp` EC2 인스턴스 역할에 아래 권한 필요:

```json
{
  "Effect": "Allow",
  "Action": [
    "cloudwatch:GetMetricStatistics",
    "cloudwatch:ListMetrics"
  ],
  "Resource": "*"
}
```

boto3는 EC2 인스턴스 역할(Instance Profile)에서 자동으로 자격증명을 가져온다.
별도 AWS_ACCESS_KEY 주입 불필요.

### 컨테이너 재시작 후 복구

`--restart unless-stopped` 옵션으로 EC2 재시작 시 자동 복구됨.
단, monitoring 네트워크는 별도로 재연결 필요 없음 — run 시 `--network monitoring` 지정으로 고정.

### Slack Incoming Webhook 발급

1. Slack 워크스페이스 → Apps → Incoming Webhooks
2. 채널 선택 → Webhook URL 복사
3. `SLACK_WEBHOOK_URL` 환경변수에 주입

---

## 6. Claude (AI) 연동

### MCP가 뭔가

**MCP (Model Context Protocol)** — Anthropic이 만든 AI 도구 연동 표준 프로토콜.
쉽게 말하면: Claude가 외부 시스템의 도구를 직접 호출할 수 있게 해주는 규격.

이 서버에서 `tools.py`가 MCP 서버 역할을 한다.
Claude가 `/mcp/sse`로 SSE 연결하면 어떤 도구가 있는지 목록을 받고, 도구를 직접 호출한다.

### 어떤 AI가 쓰이는가

**Claude** (Anthropic).
Claude Desktop 또는 Claude Code가 이 MCP 서버에 연결해서 운영 도구를 사용한다.

### 연결 방법 — Claude Desktop

`~/Library/Application Support/Claude/claude_desktop_config.json` (macOS 기준):

```json
{
  "mcpServers": {
    "campaign-monitor": {
      "url": "http://<terraform-mcp-public-ip>:8000/mcp/sse"
    }
  }
}
```

Claude Desktop을 재시작하면 좌측 도구 목록에 `campaign-monitor`가 뜬다.

### 실제 사용 시나리오

Claude Desktop에서 대화:

```
사용자: 지금 Redis Queue 상태 어때?

Claude: query_prometheus 도구를 사용해서 확인할게요.
        [도구 호출: query_prometheus("campaign_redis_queue_size")]
        → 현재 342,000건입니다. 임계값(700,000)의 34% 수준으로 정상입니다.

사용자: Kafka lag은?

Claude: [도구 호출: query_prometheus("sum(kafka_consumergroup_lag_sum)")]
        → 현재 lag은 12입니다. 정상 범위입니다 (CRITICAL 임계값: 1,000).

사용자: 아까 cpu_critical 알림 왔는데 지금은 어때?

Claude: [도구 호출: get_monitor_status()]
        → cpu_critical: 마지막 알림 240초 전, 쿨다운 잔여 60초
        [도구 호출: query_prometheus("(ASG CPU PromQL)")]
        → 현재 CPU 추이도 함께 설명...
```

### AI의 역할 범위

| 할 수 있는 것 | 할 수 없는 것 |
|--------------|--------------|
| 메트릭 조회 및 분석 | 인스턴스 재시작 |
| 이상 원인 설명 | ASG 스케일 조정 |
| 수동 감지 트리거 | 코드 배포 |
| 정합성 검사 실행 | DB 데이터 수정 |
| 쿨다운 리셋 | 인프라 변경 |

**설계 원칙: AI는 탐지와 설명만, 실행은 사람이 한다.**

---

## 7. 생각보다 금방 끝난 이유

맞다. 상대적으로 빠르게 완성됐다. 이유가 있다.

### 1단계 설계를 미리 잘 잡았다

뼈대를 먼저 잡았기 때문에 2~5단계는 **패턴을 채워넣는 작업**이었다.
모든 detector가 구조적으로 동일하다:

```
메트릭 조회 (Prometheus or CloudWatch)
    → 임계값 비교
    → can_alert() 확인
    → send_alert()
    → record_alert()
```

p1 작성하고 나면 p2, p3는 쿼리와 임계값만 바꾸면 됐다.

### 실제 복잡도가 낮은 구간

- `state.py`: dict 3개 함수, 10줄
- `slack.py`: HTTP POST 하나, 25줄
- `monitor.py`: for loop + try/except, 20줄
- `tools.py`: 도구 정의 + 핸들러, 실제 로직은 기존 함수 호출뿐

### 복잡도가 숨어있는 곳

이 서버 자체보다 **연동하는 시스템이 이미 완성돼 있었다**:

- Prometheus에 커스텀 메트릭 4종이 이미 수집 중 (Phase B에서 구축)
- CloudWatch에 ASG/RDS 메트릭이 자동 수집 중
- Slack Webhook은 기존 규격 그대로 사용
- MCP 프로토콜은 라이브러리가 핵심 처리를 담당

이 서버는 기존 인프라를 **연결하는 레이어**다.
핵심 복잡도(부하 분산, Kafka 파티셔닝, Redis 원자 처리)는 이미 Phase A/B에서 해결됐다.

### 남은 복잡도

실제로 EC2에 올려서 돌리면 생길 이슈들:

- Prometheus 메트릭 이름이 실제와 다를 경우 쿼리 조정
- CloudWatch API 레이트리밋 (30초마다 폴링 × 2개 메트릭)
- `check_consistency` API가 Spring Boot에 아직 구현 안 됐을 가능성
- MCP SSE 연결 안정성 (네트워크 끊김 시 재연결)

이런 부분은 실제 배포 후 로그 보면서 조정한다.
