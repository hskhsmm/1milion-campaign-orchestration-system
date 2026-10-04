# 2026-09-28 150만 재현 테스트 — active Set 제거 트리거 추적

> 상태: **원인 확정** — AMI(`ami-01c64e7a84a57e681`, 2026-04-27)에 남은 구버전 앱 컨테이너(`8497479`)가 새 인스턴스 부팅 시 자동 기동되어 Set을 조작한다. 2차 강제 재현으로 고립 재현(DB 394,000 + 큐 206,000 = 600,000).
>
> 후속 조치(2026-10-03): §6.2 전 항목 코드 작업 완료, AWS 반영·실환경 검증 대기 → `2026-10-03-ami-baked-container-remediation.md`

## 요약

- **원인 [확정]**: 앱 AMI에 `restart=unless-stopped`인 구버전 컨테이너 `campaign-core-app`(이미지 `batch-kafka-system:84974790…`, 2026-04-26 생성)가 구워져 있다. 새 인스턴스가 부팅하면 이 컨테이너가 자동 기동되어 CodeDeploy가 교체하기 전까지 약 2분간 prod Redis/DB에 붙어 돈다.
- 구버전 코드가 하는 일:
  - `ParticipationBridge`: 해시태그 없는 `queue:campaign:{id}` 대신 `queue:campaign:<id>`(항상 빈 키)를 `RPOP` → null → 현재 Lua와 같은 flag 키 `active:campaign:{id}`가 없으면 **즉시 `active:campaigns`에서 SREM**. 재고 소진 후라면 실제 큐 잔량과 무관하게 고립.
  - `StockRecoveryScheduler`(60초): DB OPEN인데 Redis 재고 키가 없는 캠페인을 **재고 복구 + Set 재등록** (예: campaignId=53 restoreStock 9,874,723).
- 9/19·9/25 고립, 9/20 성공, 오늘 1차 성공은 모두 "새 인스턴스 부팅 시점이 재고 소진 전인가 후인가"로 설명된다.
- 9/25 문서의 가설 (b) "`RPOP`이 비어 있지 않은 리스트에 null"은 **틀렸다**. 구버전은 다른 키를 읽었다.
- 1단계 수정(현재 코드 Bridge `LLEN` 재확인 가드)은 **이 원인을 막지 못한다**. 근본 조치는 AMI/부팅 시 구버전 컨테이너 제거(§6).
> 기준 시각: 별도 표기가 없으면 **KST**. CloudWatch/ASG 원본은 UTC이며 +9h로 환산했다.
> 이전 기록: `2026-09-25-150m-retest-queue-residue-analysis.md` (고립 재발, 트리거 미확정)
> 근거 등급: **[확정]** 원본 데이터로 확인 / **[추정]** 데이터에서 계산·추론 / **[미확정]** 증거 없음

---

## 0. 목적

9/19, 9/25 두 번 모두 재고 소진 후 캠페인이 `active:campaigns`에서 빠져 Redis 큐 약 130만 건이 고립됐다.
두 번 모두 인스턴스 종료로 로그가 사라져 **Set 제거 트리거를 확정하지 못했다**.

이번 테스트는 9/25와 **같은 코드·같은 조건**으로 재현하고, 이전에 없던 증거 두 가지를 확보한다.

1. Redis 상태 1초 단위 기록 (`inSet`, `flag`, `llen`, `stock`, Set 전체) → 제거 시각과 잔량을 초 단위로 확정
2. 환경을 내리기 전에 앱 인스턴스 전체(합류 인스턴스 포함) 로그 확보 → 제거한 주체(인스턴스/경로) 확정

---

## 1. 사전 분석: 9/25 데이터 재조회 결과 (2026-09-28)

9/25 문서 작성 이후 AWS에 남은 기록(CodeDeploy, ASG, ElastiCache 1분 지표)을 다시 조회했다.

### 1.1 PR #96 빌드 배포 여부 → [확정] 배포됨

| 배포 ID | 생성 주체 | 리비전 | 대상 | 완료 |
|---|---|---|---|---|
| d-OH2OKDQZK / d-16V5O4QZK | autoscaling | `73db095` | 기동 인스턴스 2대 | 02:56 |
| **d-ZHS235RZK** | user | **`bc294b4` (PR #96 머지)** | `i-0da4c5c7…` 03:17:47, `i-06429ab4…` 03:18:47 Succeeded | 03:18:48 |
| d-NKOWLZQZK | autoscaling | `bc294b4` | `i-055102c9…` (3번째) | 03:36:36 |

- 9/25 문서 §6.4 "미검증" 해소. 테스트(03:27) 시점 기존 2대 모두 PR #96 코드.
- 따라서 Queue Gauge 0 낙하 = 모든 JVM에서 `SMEMBERS` 결과에 62가 없었다는 의미로 해석 가능. [확정: 코드+배포]

### 1.2 새 인스턴스는 낙하 이전에 Redis에 연결 → 9/25 문서 판단 정정

9/25 문서 §6.3은 "낙하(03:35:50)가 InService(03:36:38)보다 앞서므로 새 인스턴스와 무관"이라고 판단했다.
**앱 컨테이너는 CodeDeploy 검증 완료(InService) 이전에 기동**하므로 이 근거는 성립하지 않는다.

ElastiCache 1분 지표 (primary 노드):

| 노드 | 03:34 NewConn | **03:35 NewConn** | 03:36 NewConn | CurrConnections 변화 |
|---|---|---|---|---|
| 0001-001 | 1 | **29** | 1 | 10~11 → 12 |
| 0002-001 | 1 | 2 | **30** | 10~11 → 12 |
| 0003-001 (`{62}` 샤드) | 1 | **30** | 7 | **8 → 12** |

- 테스트 내내 NewConnections는 분당 0~4. 03:35~03:36에만 노드당 약 30건. [확정]
- 새 인스턴스 배포 d-NKOWLZQZK 구간(03:34:18~03:36:36)과 일치 → 새 JVM이 03:35 중 Redis 연결. [추정: 연결 주체는 지표상 식별 불가]
- 시점: **03:35 새 JVM 연결 → 03:35:50 Gauge 낙하 → 03:36:10 Bridge 정지**.

### 1.3 실패/성공과 JVM 합류의 상관

| 날짜 | 드레인 중 JVM 합류 | 결과 |
|---|---|---|
| 9/19 | 있음 (수동 증설) | 고립 |
| 9/20 | 없음 (처음부터 3대) | 성공 |
| 9/25 | 있음 (알람 증설) | 고립 |

현재 확보된 상관관계 중 가장 강하다. 단, 새 JVM이 Set에서 제거하는 코드 경로는 찾지 못했다 (기동 시 Redis 초기화 코드 없음, 배포 스크립트/ops에 Redis 조작 없음). [미확정]

### 1.4 Bridge pop 총량 재계산 → 약 19만 [추정]

샤드 0003 `EvalBasedCmds` 합(03:27~03:34) = **1,600,011** = k6 요청 160만과 일치. [확정]

- 03:27~03:36 `ListBasedCmds` 합 = 3,207,085
- Lua list 명령 = 접수 150만 × (`LLEN`+`LPUSH`) = 3,000,000 (재고 소진 후 10만 건은 `EXISTS`에서 반환, list 명령 없음)
- 다른 캠페인 Bridge 순회 기본값 ≈ 사이클 1,111회 × 사이클당 ~9 ≈ 10K
- 남는 값 ≈ **19만 = 캠페인 62의 `RPOP` 총량** (9/19 DB 194,500과 근접)

### 1.5 미해명 이상: 테스트 후 사이클당 명령 수 약 2배 (→ §3.3에서 해명: 구버전 `StockRecoveryScheduler`의 Set 재등록)

`SetBasedCmds`(샤드 0003) = Bridge `SMEMBERS` = 사이클 수로 정규화:

| 구간 | 사이클/분 | list/사이클 (3샤드 합) | key/사이클 (3샤드 합) |
|---|---|---|---|
| 03:20~03:24 (테스트 전) | ~825 | ~33 | ~11 |
| 03:26 (62 생성 직후) | 799 | ~36 | ~12 |
| 03:37~03:48 (테스트 후) | ~955 | **~62** | **~21** |

- 활성+빈 큐 캠페인 1개당 사이클 패턴 = list 3 (`LLEN`, `RPOP`, legacy `RPOP`) + key 1 (`EXISTS`).
- 테스트 후 증가분(list +27, key +9)은 **이 패턴의 캠페인 약 9개가 Set에 늘어난 모양**과 일치. 62가 빠지기만 했다면 오히려 감소해야 한다.
- Set 실제 내용이 남아 있지 않아 원인 미확정. 이번 테스트의 Set 전체 기록으로 확인한다. [미확정]

---

## 2. 테스트 조건

| 항목 | 값 | 9/25 대비 |
|---|---|---|
| 앱 코드 | main `bc294b4` (ASG가 마지막 성공 배포 리비전 사용) | 동일 |
| 앱 | t3.small 2대 시작, ASG min 2 / max 3, 알람 증설 허용 | 동일 |
| 부하 | terraform-mcp k6, 기존 체크아웃 스크립트(미 pull) | 동일 |
| 캠페인 | 신규, 재고 1,500,000 | 동일 |
| 요청 | 1,600,000, VU 3,000 | 동일 |
| 추가 계측 | Redis 1초 폴링, 전 인스턴스 로그 수집 | **신규** |

증거 수집 스크립트: `ops/scripts/redis-watch-start.sh`, `redis-watch-fetch.sh`, `collect-app-logs.sh`
원본 증거: `evidence/<날짜>/` (저장소 미포함)

---

## 3. 진행 기록

| 시각 | 작업 | 결과 |
|---|---|---|
| 17:3x | `make env-status` | ASG 0, Kafka·RDS·terraform-mcp stopped, Redis 없음 |
| 17:3x | `make env-up` 시작 | 17:43:53 완료 (failed=0) |
| 17:41:16~17:43:38 | ASG 기동 배포 d-HZCV5642L / d-OSR89V32L | 리비전 `bc294b4` (9/25와 동일) [확정] |
| 17:43:53 | ASG 상태 | min 2 / max 3 / desired 2, `i-0478bca4…`, `i-086e8332…` InService |
| 17:44 | ALB `/actuator/health` | `UP` |
| 17:44 | terraform-mcp 저장소 확인 | `fbbbc11`(PR #86) 체크아웃, 구 `k6-load-test.js`(임계값 `count>9900`, SharedArray userId, 100건마다 로그) — 9/25와 동일 |
| 17:44 | 9/25 실행 명령 확인 (bash_history) | `CAMPAIGN_ID=62 TOTAL_REQUESTS=1600000 MAX_VUS=3000 DURATION=3600 run-test.sh prod` |
| 17:44:57 | 캠페인 생성 `POST /api/admin/campaigns` | **id=63**, totalStock 1,500,000, OPEN |
| 17:45:09 | `redis-watch-start.sh 63` 1차 | 실패: SSM 파라미터가 SecureString인데 `--with-decryption` 누락 → 스크립트 수정 |
| 17:45:24 | `redis-watch-start.sh 63` 2차 | 정상. 초기 상태 `inSet=1 flag=1 llen=0 stock=1500000` |
| 17:45:24 | **초기 Set 내용** | `[1,2,3,8,9,13,14,15,17,19,63]` — **새로 만든 Redis인데 이전 캠페인 10개가 이미 등록돼 있음** (등록 주체 미확인, §5에서 추적) |
| **17:45:39** | **k6 시작** (terraform-mcp, `CAMPAIGN_ID=63 TOTAL_REQUESTS=1600000 MAX_VUS=3000 DURATION=3600`) | SSM 대기는 시간 초과났으나 프로세스 정상 실행 확인 |
| 17:48:42 | k6 진행 | iteration 약 492,000 |

### 3.1 1차 실행(캠페인 63) 30초 단위 진행 (progress 루프 원본)

| 시각 | ASG | k6 | inSet | flag | llen | stock | Set 크기 |
|---|---|---|---|---|---|---|---|
| 17:49:12 | 3 (3번째 `i-08786efc…` **Pending:Wait**) | 607,300 | 1 | 1 | 530,733 | 887,204 | 11 |
| 17:49:44 | 3 (Pending:Wait) | 743,100 | 1 | 1 | 649,938 | 754,405 | 11 |
| 17:50:18 | 3 (Pending:Wait) | 875,900 | 1 | 1 | 768,569 | 621,749 | 11 |
| 17:50:50 | 3 (Pending:Wait) | 1,022,600 | 1 | 1 | 898,794 | 480,714 | **19** (+25,26,30,31,32,33,35,36) |
| 17:51:23 | 3 (Pending:Wait) | 1,150,236 (72%) | 1 | 1 | 1,024,672 | 349,596 | **20** (+39) |
| 17:51:56 | 3 (**InService**) | 1,263,348 (79%) | 1 | 1 | 1,130,460 | 237,185 | 20 |
| 17:52:29 | 3 | 1,340,700 | 1 | 1 | 1,200,684 | 161,287 | 20 |
| 17:53:00 | 3 | 1,453,100 | 1 | 1 | 1,307,102 | 50,376 | 20 |
| **17:53:35** | 3 | 1,552,200 (400 응답 시작) | 1 | **0** | **1,354,786** | **0** | 20 |
| 17:53:52 | — | **k6 종료** (임계값 `http_req_duration`, `http_req_failed` 초과) | | | | | |
| 17:54:08 | 3 | done | 1 | 0 | 1,327,707 | 0 | 20 |
| 17:54:40 | 3 | done | 1 | 0 | 1,257,000 | 0 | 20 |
| 17:55:14 | 3 | done | 1 | 0 | 1,203,957 | 0 | 20 |
| 17:55:47 | 3 | done | 1 | 0 | 1,128,649 | 0 | 20 |
| 17:56:20 | 3 | done | 1 | 0 | 1,074,618 | 0 | 20 |
| 17:56:53 | 3 | done | 1 | 0 | 1,001,871 | 0 | 20 |
| 17:57:25 | 3 | done | 1 | 0 | 951,144 | 0 | 20 |
| 18:00:45 | 3 | done | 1 | 0 | 569,546 | 0 | 20 |
| 18:01:18 | 3 | done | 1 | 0 | 520,000 | 0 | 20 |
| 18:01:51 | 3 | done | 1 | 0 | 445,768 | 0 | 20 |
| 18:02:24 | 3 | done | 1 | 0 | 386,641 | 0 | 20 |
| 18:02:57 | 3 | done | 1 | 0 | 310,555 | 0 | 20 |
| 18:03:30 | 3 | done | 1 | 0 | 251,692 | 0 | 20 |
| 18:04:04 | 3 | done | 1 | 0 | 186,502 | 0 | 20 |
| 18:04:37 | 3 | done | 1 | 0 | 118,986 | 0 | 20 |
| 18:05:10 | 3 | done | 1 | 0 | 61,493 | 0 | 20 |
| **18:05:43** | 3 | done | **0** | 0 | **0** | 0 | 19 (63 제거) |

(17:57:25~18:00:45 사이 3분 공백은 progress 루프의 SSM 응답 지연. 1초 원본 기록에는 공백 없음.)

1차 관찰 [확정]:
- **고립 재현 안 됨.** 63은 `llen=0`이 된 뒤(18:05:10~18:05:43 사이) Set에서 정상 제거.
- 3번째 인스턴스는 **재고 소진 전**(17:49 이전 기동, 17:51:56 InService)에 합류. 9/25(소진 후 드레인 중 합류)와 조건이 다름.
- 테스트 중 Set에 이전 캠페인 9개(25,26,30,31,32,33,35,36,39)가 **추가**됨. 9/25 §1.5의 "사이클당 명령 약 2배" 현상과 일치. 추가 시점(17:50:18~17:51:23)은 3번째 인스턴스 기동 구간.
- 드레인 속도(3대, 유입 종료 후): 17:54:08 → 18:05:43 동안 1,327,707건 ≈ **약 1,910/s**.

### 3.2 1차 실행 후 증거 수집

| 시각 | 작업 | 결과 |
|---|---|---|
| 18:07 | `redis-watch-fetch.sh --stop` → `evidence/20260928/run1-c63/redis-watch.log` | 1,197줄 (17:45:24~18:06:56) |
| 18:07~ | `collect-app-logs.sh` | `i-0478bca4…` 47,081,806 bytes 수집, 나머지 진행 중 (SSM 20,000자 단위 전송이라 인스턴스당 수 분) |

1초 기록에서 뽑은 상태 변화 지점 [확정]:

| 시각 | 이벤트 | llen |
|---|---|---|
| 17:45:24 | 감시 시작, Set `[1,2,3,8,9,13,14,15,17,19,63]` | 0 |
| **17:50:26** | Set에 **25,26,30,31,32,33,35,36 추가** | 804,887 |
| **17:50:58** | Set에 **39 추가** | 929,970 |
| **17:53:19** | flag 삭제 (재고 0) | **1,357,599 (최대)** |
| **18:05:40** | 63 Set에서 제거 | 0 |

- `i-0478bca4…` 로그: `18:05:40.006 [scheduling-2] ParticipationBridge : Campaign drained and deactivated. campaignId=63` — 현재 코드의 정상 경로로 제거. [확정]
- 같은 로그의 ERROR 3건(17:53:19, 17:55:35, 17:57:25)은 `NoResourceFoundException: No static resource for request '/'`(외부 `/` 요청)로 무관. WARN 32,793건은 전부 `PARTICIPATION_004 재고 소진. campaignId=63`.

### 3.3 Set 추가 주체 추적 → **AMI에 구워진 구버전 앱 컨테이너** [확정: 인스턴스 기록 + 코드]

배제한 경로:
- 정합성 복구 실행 이력(`GET /api/admin/consistency-recovery/executions`): 2건뿐(2026-05-02 id=1, 2026-09-19 id=2), 둘 다 `dryRun=true, autoFix=false`. [확정]
- mcp-server 로그(08:30Z~): 30초 주기 `run_monitor`(Prometheus 조회)만 존재, 앱 API 쓰기 호출 없음. `run_consistency_check` 잡은 08:40:50Z 등록만 되고 실행 로그 없음. [확정]
- 현재 코드(`bc294b4`)에서 `active:campaigns`에 추가하는 경로는 `CampaignService.create`와 `ConsistencyRecoveryService`(autoFix)뿐. 기동 훅(`@PostConstruct`, Runner, `@EventListener` 등) 없음. [확정: 코드]

3번째 인스턴스 `i-08786efc…` 기록:

| 시각 (UTC) | 이벤트 | 출처 |
|---|---|---|
| 08:48:54 | 부팅 | `uptime -s` |
| 08:49:08 | dockerd가 **이전 컨테이너 sandbox 2개 복원** (`Removing stale sandbox 1f748ed5…`, `75f6eda6…`) | `journalctl -u docker` |
| 08:49:10 | Docker 기동 완료 → restart policy로 **이전 컨테이너 2개 자동 기동** | 〃 |
| **08:50:26** | Set에 캠페인 8개 추가 | redis-watch |
| **08:50:58** | Set에 캠페인 1개 추가 | redis-watch |
| 08:51:09~10 | 이전 컨테이너 `a5f68e64…`, `537ec375…` 종료 (`ShouldRestart failed, container will not be restarted`) — 배포 스크립트가 교체 | `journalctl -u docker` |
| 08:51:11 | 현재 앱 컨테이너 `campaign-core-app`(`bc294b4`) 시작 | `docker inspect` |

- 인스턴스에 남은 이미지: `batch-kafka-system` 태그 21개(5개월 전, 4/17~4/26 커밋) + `<none>` 1개 + `oliver006/redis_exporter`. 남은 컨테이너: `batch-kafka-app`(`12786404…`, 2026-04-17 생성, Created 상태).
- Launch Template v3 AMI: `ami-01c64e7a84a57e681` (`batch-kafka-app-ami`, **2026-04-27 생성**).
- AMI 내 최신 이미지 태그: `8497479` (2026-04-26, PR #53). [추정: 자동 기동된 컨테이너의 이미지가 이 태그라는 점은 컨테이너가 삭제돼 직접 확인 불가]

`8497479` 코드:

```java
// StockRecoveryScheduler (60초 주기) — 현재 코드에는 없음
List<Campaign> activeCampaigns = campaignRepository.findByStatus(CampaignStatus.OPEN);
for (Campaign campaign : activeCampaigns) {
    if (redisStockService.hasStock(campaign.getId())) continue;   // stock:campaign:{id} 없으면
    ... initializeStock / initializeTotal / activateCampaign(...)   // → active:campaigns SADD
}

// ParticipationBridge
private static final String QUEUE_KEY_PREFIX = "queue:campaign:";   // 해시태그 없음
String queueKey = QUEUE_KEY_PREFIX + campaignId;                    // queue:campaign:62
String message = redisTemplate.opsForList().rightPop(queueKey);
if (message == null) {
    if (!redisStockService.isActive(campaignId)) {                  // active:campaign:{62} — 현재 Lua와 같은 키
        redisStockService.deactivateCampaign(campaignId);           // → active:campaigns SREM
```

- **Set 추가**: 새로 만든 Redis에는 `stock:campaign:{id}`가 없으므로 DB에서 OPEN인 이전 캠페인을 전부 재활성화 → 초기 Set 10개(1·2차 인스턴스 부팅 17:41 직후)와 17:50:26/58 추가 9개를 설명.
- **Set 제거(9/19·9/25 고립의 유력 메커니즘)**: 구버전 Bridge는 **해시태그 없는 `queue:campaign:62`**(항상 빈 키)를 읽으므로 `RPOP`이 항상 null. flag 키는 현재 Lua와 같은 `active:campaign:{62}`를 본다. 따라서
  - 재고 소진 **전**(flag 있음): 제거 안 함 → **오늘 1차 실행**(구버전이 17:49~17:51, 소진은 17:53:19)
  - 재고 소진 **후**(flag 없음): 실제 큐(`queue:campaign:{62}`) 잔량과 무관하게 **즉시 SREM** → **9/25**(새 인스턴스 부팅 03:34:13, 구버전 기동 ~03:35, Gauge 낙하 03:35:50)
- 9/25 문서 §6.3의 "`RPOP`이 비어 있지 않은 리스트에 null" 가설 (b)는 틀렸다. `RPOP` 대상 키 자체가 달랐다.
- 9/25 §1.2의 03:35 NewConnections 약 30건, §1.5의 테스트 후 사이클당 명령 약 2배(Set 캠페인 약 9개 증가)도 같은 원인으로 설명된다.
- **1단계 수정(Bridge `LLEN` 재확인 가드)은 이 원인을 막지 못한다.** 가드는 현재 코드에만 있고, 제거를 수행하는 것은 구버전 컨테이너다.

### 3.4 2차 실행: 강제 재현 (드레인 중 JVM 합류)

목적: 3.3의 메커니즘을 직접 확인. 재고 소진(flag 삭제) **직후, 큐 잔량이 있을 때** 새 인스턴스를 붙이고, 부팅 시 자동 기동되는 구버전 컨테이너의 이미지·로그를 교체 전에 캡처한다.

| 시각 | 작업 | 결과 |
|---|---|---|
| 18:30:41 | `suspend-processes AlarmNotification` (부하 중 자동 증설 차단, 수동 증설은 가능) + desired 3→2 | 정상 |
| 18:30:41~18:37:24 | scale-in | `i-086e8332…` 종료 (로그는 3.2에서 수집 완료). 남은 인스턴스 `i-0478bca4…`, `i-08786efc…` |
| 18:37:35 | 캠페인 생성 | **id=64**, totalStock 600,000 |
| 18:37:38 | `redis-watch-start.sh 64` | `inSet=1 flag=1 llen=0 stock=600000`, Set 20개 (19개 이전 캠페인 + 64) |
| 18:37:52 | k6 시작 (`CAMPAIGN_ID=64 TOTAL_REQUESTS=620000 MAX_VUS=3000 DURATION=3600`) | 18:37:57 llen 2,095 |
| 18:37:53~ | 트리거 스크립트: flag=0 감지 → desired 3 → 새 인스턴스에 `capture-boot-containers.sh` | 아래 |
| **18:40:30** | flag 삭제 (1초 기록) | llen 530,811 |
| 18:40:35 | 트리거 감지 → `set-desired-capacity 3` | |
| 18:40:47 | 새 인스턴스 `i-035e5006…` 기동 (ASG) | |
| 18:40:51 | CodeDeploy d-DFYY2G52L 생성 (autoscaling) | |
| 18:41:09 | SSM Online → 캡처 시작 (1차 버전: 2초 간격, 전 컨테이너 전체 로그) | |
| 18:41:07 | 해당 인스턴스 SSM `ConnectionLost` (이후 복구 안 됨) | |
| **18:42:37 → 18:42:38** | **64가 Set에서 제거** (새 인스턴스는 `Pending:Wait`, 현재 코드 미배포) | llen 211,595 → **208,577** |
| 18:42:39~ | 큐 정지 | 206,341 → **206,000 고정** (제거 직전 사이클의 in-flight 처리분) |
| 18:42:58~18:43:39 | Set에 40,41,43,44,45,46,47,48,49,50,51 하나씩 추가 | 구버전 `StockRecoveryScheduler` 패턴 |
| 18:43:32~ | CodeDeploy `AfterInstall` InProgress에서 정지 | ApplicationStop/DownloadBundle/BeforeInstall(18:41:05~18:43:32)/Install은 Succeeded |
| 18:57:55 | 현재 코드 JVM 2대: `Campaign drained and deactivated. campaignId=52` | 64 제거 로그는 **없음** (`i-0478bca4…` 9,702줄, `i-08786efc…` 10,299줄 중) |
| 19:09 | `i-035e5006…` 상태: SSM ConnectionLost, EC2 CPU 5분 평균 34~61% (최대 95.7%) | 콘솔 출력에 OOM/hung 흔적 없음 |
| 19:11:29 | `reboot-instances i-035e5006…` (컨테이너 로그 회수 목적) | |
| 19:15:45 | ASG가 `i-035e5006…` 종료: `Lifecycle Action ... abandoned: ABANDON Result` | 캡처 파일 유실 |
| 19:16:46 | ASG 대체 인스턴스 `i-0a0b7920…` 기동 | |
| 19:16:51 | `i-0a0b7920…` 부팅 | |
| **19:17:05** | **구버전 컨테이너 자동 기동**: `537ec3755dbe /campaign-core-app` image `batch-kafka-system:84974790…` created 2026-04-26T07:33:57Z restart=`unless-stopped` / `a5f68e64ead7 /redis-exporter` | 캡처(2차 버전: 10초 간격, 현재 이미지 제외, `--tail 3000`) [확정] |
| 19:17:12 | 구버전 로그: `The following 1 profile is active: "prod"` | |
| 19:17:49 | 구버전 로그: `Started CampaignCoreApplication in 39.136 seconds` | |
| **19:17:54** | 구버전 로그: `StockRecoveryScheduler : Redis 재고 복구. campaignId=53, restoreStock=9874723, success=125276, ...` | Set 추가 경로 직접 확인 [확정] |
| 19:18:15 | 구버전 로그: `Redis 재고 복구. campaignId=54, restoreStock=8909447, success=1090552, ...` | |
| 19:18:xx | 64를 Set에 재등록해 구버전 Bridge의 제거 로그를 직접 관찰하려 했으나 **Redis 쓰기 권한 거부로 미실행** | |
| 19:19 | `resume-processes AlarmNotification` | 복구 완료 |
| 19:11~19:29:53 | 2차 로그 수집 (`INSTANCE_IDS` 지정) | `i-0478bca4…` 65,170,415 B, `i-08786efc…` 54,320,193 B. 두 로그의 제거 로그는 18:05:40(63), 18:57:55(52)뿐 |
| 19:29:5x | redis-watch 컨테이너 종료 | 마지막 기록 19:10:35 `inSet=0 llen=206000` |
| 19:29:56~19:44:44 | `make env-down` | failed=0 (ASG 0, EC2/RDS stop, Redis destroy) → 64 큐 잔량 206,000은 Redis와 함께 삭제 |

- `537ec375…`, `a5f68e64…`는 1차 실행 때 `i-08786efc…`의 dockerd가 08:51:10Z에 종료한 컨테이너 ID와 **동일**. 즉 AMI에 구워진 같은 컨테이너가 부팅마다 되살아난다. [확정]
- `i-035e5006…` 무응답 원인: t3.small(2GB)에서 구버전 앱 + redis-exporter + Ansible 배포 + 1차 캡처 루프(2초마다 전체 `docker logs`)가 겹친 자원 고갈로 **추정**. 캡처 루프를 경량화(10초, `--tail`)한 2차에서는 재발하지 않았다. [추정]

---

## 4. 결과

### 4.1 1차 실행 (캠페인 63, 9/25 동일 조건) — 고립 재현 안 됨 [확정]

k6 원본 (`evidence/20260928/run1-c63/k6-summary.txt`):

```
running (0h08m07.3s), 0000/3000 VUs, 1600000 complete and 0 interrupted iterations
participation_fail.............: 100000  205.218344/s
participation_success..........: 1500000 3078.275161/s
http_req_duration..............: avg=909.39ms min=2.14ms med=461.87ms max=19.73s p(90)=2.18s p(95)=3.06s
http_req_failed................: 6.25%   100000 out of 1600000
http_reqs......................: 1600000 3283.493505/s
thresholds on metrics 'http_req_duration, http_req_failed' have been crossed
```

DB (`GET /api/campaigns/63/status`, 18:3x 조회):

| 항목 | 값 |
|---|---|
| successCount | **1,500,000** |
| failCount | 0 |
| totalParticipation | 1,500,000 |
| currentStock | 0 |
| stockUsageRate | 100.00% |

앱 인스턴스 (`collect-app-logs.sh`):

| 인스턴스 | 부팅(UTC) | 현재 앱 컨테이너 시작(UTC) | `Started CampaignCoreApplication` (KST) | 로그 크기 | 63 제거 로그 (KST) |
|---|---|---|---|---|---|
| `i-0478bca4…` | 08:41:16 | 08:43:08 | 17:43:32 (22.077s) | 47,081,806 B | 18:05:40.006 `scheduling-2` |
| `i-086e8332…` | 08:41:16 | 08:43:09 | 17:43:33 (21.582s) | 58,170,236 B | 18:05:39.999 `scheduling-2` |
| `i-08786efc…` | 08:48:54 | 08:51:11 | 17:51:37 (23.446s) | 36,259,584 B | 18:05:40.014 `scheduling-2` |

- 세 JVM 모두 같은 순간 `RPOP null → flag 없음`을 보고 `deactivateCampaign` 호출(SREM은 멱등이라 무해).
- 9/25 대비: p95 3.14s → 3.06s, 처리 3,330/s → 3,283/s, 재고 초과 거절 100,000건 동일.

### 4.2 2차 실행 (캠페인 64, 드레인 중 강제 합류) — **고립 재현** [확정]

k6 원본 (`evidence/20260928/run2-c64/k6-summary.txt`):

```
participation_fail.............: 20000  122.831349/s
participation_success..........: 600000 3684.940476/s
http_req_duration..............: avg=777.12ms min=1.74ms med=494.18ms max=11.29s p(90)=1.69s p(95)=2.25s
http_req_failed................: 3.22%  20000 out of 620000
http_reqs......................: 620000 3807.771825/s
running (0h02m42.8s), 0000/3000 VUs, 620000 complete and 0 interrupted iterations
```

| 항목 | 값 |
|---|---|
| k6 202 | 600,000 |
| DB successCount (19:19 조회) | **394,000** |
| Redis `queue:campaign:{64}` 잔량 | **206,000** (18:42:39 이후 19:10:35까지 고정) |
| 합계 | **600,000 = 재고** (유실 없음, 고립만 발생) |
| 64 Set 제거 시각 | 18:42:38 (flag 삭제 후 128초, 새 인스턴스 기동 후 111초) |
| 현재 코드 JVM의 64 제거 로그 | 없음 |

## 5. 분석

### 5.1 Set을 제거한 주체 (소거법 + 코드 + 시점) [확정]

Redis `active:campaigns`에 쓰는 클라이언트는 앱 인스턴스뿐이다(mcp-server는 Prometheus 조회와 정합성 dry-run POST만, redis-exporter는 읽기 전용).

| 후보 | 64 제거 가능성 | 근거 |
|---|---|---|
| `i-0478bca4…` 현재 코드 | ❌ | 해당 시간대 제거 로그는 18:57:55 campaignId=52 1건뿐 |
| `i-08786efc…` 현재 코드 | ❌ | 〃 |
| `i-035e5006…` 현재 코드 | ❌ | CodeDeploy `AfterInstall`에서 정지 → 현재 앱 미기동 |
| **`i-035e5006…` AMI 구버전 컨테이너** | ✅ | 부팅 후 자동 기동(동일 AMI의 `i-0a0b7920…`에서 이미지·restart 정책 확인), 구버전 Bridge는 빈 키를 읽고 flag 없으면 SREM, 제거 시각이 기동 후 약 1분 50초, 직후 `StockRecoveryScheduler` 패턴의 Set 추가 발생 |

- 구버전 컨테이너 자체의 "64 제거" 로그 한 줄은 인스턴스 유실로 확보하지 못했다. 위 소거법과 코드, 시점의 일치로 확정으로 판정한다.

### 5.2 9/19 · 9/20 · 9/25 · 9/28 재해석

| 날짜 | 드레인 중 신규 인스턴스 부팅 | 부팅 시점 flag | 결과 |
|---|---|---|---|
| 9/19 | 있음 (알람 증설 → 라이프사이클 실패, 이후 수동 증설) | 없음(소진 후) | 고립 (DB 194,500 / Queue 1,305,500) |
| 9/20 | 없음 (처음부터 3대) | — | 성공 |
| 9/25 | 있음 (알람 03:34:06 → 부팅 03:34:13, 구버전 기동 ~03:35) | 없음(소진 ~03:35) | 고립 (Gauge 03:35:50 낙하) |
| 9/28 1차 | 있음 (17:48:54 부팅) | **있음**(소진 17:53:19) | 성공 |
| 9/28 2차 | 있음 (18:40:47 기동, 강제) | 없음(소진 18:40:30) | **고립 재현** |

- 9/25 §5.2의 "03:36 이후 Bridge 정상 순회인데 62 큐 pop 없음", §1.2의 03:35 NewConnections 약 30건, §1.5의 테스트 후 Set 캠페인 약 9개 증가가 모두 이 원인으로 설명된다.
- 9/25 문서 §6.3 "기동 시 초기화 코드는 소스 전체에 없음"은 **현재 소스 기준으로는 맞지만**, 실제로 부팅 시 돈 것은 현재 소스가 아니었다.

### 5.3 부수 발견

1. **구버전 `StockRecoveryScheduler`가 끝난 캠페인의 재고를 되살린다.** DB 상태가 OPEN으로 남은 과거 테스트 캠페인(1,2,3,8,9,13~19,25~54 등)에 수백만 단위 재고를 다시 쓰고 flag·Set을 등록한다 → 이 캠페인들이 다시 참여 가능한 상태가 된다. [확정: 로그 campaignId=53 restoreStock 9,874,723]
2. 새로 만든 Redis에도 env-up 직후 이전 캠페인 10개가 Set에 있던 이유: 1·2번 인스턴스 부팅(17:41:16) 직후 구버전이 약 2분간 돌며 등록. [추정: 같은 메커니즘, 해당 인스턴스 부팅 로그 미수집]
3. 드레인 속도: 3대 약 1,910/s(1차), 2대 18:40:30~18:42:37 530,811→211,595 ≈ **약 2,500/s** (유입 종료 직후).
4. 오토스케일링: 1차에서는 부하 시작 약 3분 만에 3번째가 기동(17:48:54 부팅)해 9/25(약 7분)보다 빨랐다. 원인 미분석.

## 6. 결론 / 후속 조치

### 6.1 결론

- 9/19·9/25 Redis 큐 고립의 원인은 **AMI에 남은 2026-04-26 버전 앱 컨테이너가 신규 인스턴스 부팅 시 자동 기동**되는 것이다. 현재 코드의 결함이 아니라 **배포/이미지 위생 문제**다.
- 재현 조건: "재고 소진(flag 삭제) 후, 큐 잔량이 남아 있을 때 신규 앱 인스턴스 부팅".

### 6.2 조치 (우선순위)

| 순위 | 조치 | 비고 |
|---|---|---|
| P0 | AMI 재생성: 컨테이너·구 이미지·`/opt/campaign-core` 잔재 없는 AMI로 교체 후 Launch Template 새 버전 | Terraform 변경, apply는 사람이 실행 |
| P0 (즉시 완화) | user-data 맨 앞에서 `docker rm -f $(docker ps -aq)` 또는 docker 기동 전 컨테이너 restart 정책 제거 | AMI 재생성 전 임시 방어 |
| P1 | 해시태그 없는 legacy 키를 읽는 코드/Bridge legacy 드레인 정리 | 현재 코드의 `LEGACY_QUEUE_KEY_PREFIX` 드레인 루프 포함 검토 |
| P1 | 과거 테스트 캠페인 DB 상태 정리(CLOSED) | 구버전이 아니어도 정합성 검사·Set 순회 비용 |
| P2 | 1단계 Bridge `LLEN` 재확인 가드 | 원인 방어는 아니지만 방어적 가드로 유지 가치 있음 (브랜치 `fix/bridge-queue-orphan-guard`) |
| P2 | QueueMetricsScheduler 실제 LLEN 검증 (inactive → 강제 0 제거) | 고립이 0으로 위장되는 문제 |

### 6.3 이번 작업의 한계 / 정정

- 구버전 컨테이너가 64를 SREM한 로그 한 줄은 확보하지 못했다(인스턴스 무응답 → ABANDON 종료). Redis 재등록으로 직접 관찰하려던 시도는 권한 거부로 실행하지 않았다.
- `i-035e5006…` 무응답에는 1차 캡처 루프의 부하가 기여했을 가능성이 있다. [추정]
- 1차 실행에서 17:57:25~18:00:45 progress 루프 기록 공백은 SSM 응답 지연이며 1초 원본에는 공백이 없다.

---

## 7. 부록

### 7.1 `queue:campaign:{64}` vs `queue:campaign:64` — 왜 다른 키인가

Redis 키는 문자열이다. 중괄호 유무만 달라도 **완전히 다른 키**다.

| 키 | 누가 쓰나 | 내용 |
|---|---|---|
| `queue:campaign:{64}` | 현재 코드 Lua `check-decr-enqueue.lua`가 `LPUSH` | 실제 참여 메시지 (2차 실행 시 60만 적재, 206,000 잔존) |
| `queue:campaign:64` | 아무도 쓰지 않음 (해시태그 전환 이전 이름) | 항상 비어 있음 → 구버전 Bridge의 `RPOP`은 항상 null |

중괄호는 Redis Cluster **해시태그**다.

- ElastiCache 클러스터 모드(3샤드)는 키 이름의 CRC16 해시로 슬롯(샤드)을 정한다.
- 중괄호가 없으면 **키 전체 문자열**로 해시 → `stock:campaign:64`, `queue:campaign:64`, `active:campaign:64`가 서로 다른 샤드에 흩어질 수 있다.
- 중괄호가 있으면 **`{}` 안의 문자열만**으로 해시 → `stock:campaign:{64}`, `queue:campaign:{64}`, `active:campaign:{64}`, `participated:campaign:{64}:user:*`가 모두 같은 슬롯에 모인다.
- 필요한 이유: Lua 스크립트는 한 번에 한 슬롯의 키만 다룰 수 있다(다른 슬롯이 섞이면 `CROSSSLOT` 에러). 재고 확인·차감·큐 적재를 원자적으로 하려면 관련 키가 같은 슬롯에 있어야 한다.
- 도입 커밋: `e3bfd7f` (2026-04-24) "refactor(redis): 해시태그 키 구조 전환 및 active 플래그 분리".
- 참고: 9/25 §5.2 ④ "모든 캠페인 62 키가 `{62}` 해시태그로 샤드 0003 한 곳에 몰림"도 같은 이유다.

구버전 `8497479`(2026-04-26)는 전환 과도기 버전이라 키가 섞여 있었다:

| | 큐 키 (Bridge `RPOP`) | flag 키 (`isActive`) |
|---|---|---|
| 현재 코드 `bc294b4` | `queue:campaign:{id}` | `active:campaign:{id}` |
| 구버전 `8497479` | `queue:campaign:<id>` ← **구 이름** | `active:campaign:{id}` ← 신 이름 |

→ 큐는 "비었다"(구 이름이라 항상 빔), flag는 "없다"(신 이름이라 Lua 삭제를 정확히 봄)가 동시에 성립해 SREM이 실행된다. 둘 다 구 이름이거나 둘 다 신 이름이었다면 이 조합은 생기지 않는다.

현재 코드에도 `LEGACY_QUEUE_KEY_PREFIX = "queue:campaign:"` 드레인 루프(롤링 배포 전환용)가 남아 있다 (`ParticipationBridge.java`). 동작상 문제는 없지만 전환이 끝났으므로 정리 대상이다(§6.2 P1).

### 7.2 오늘 선행 작업: Bridge `LLEN` 재확인 가드 (브랜치 `fix/bridge-queue-orphan-guard`)

원인 확정 전, 9/25 문서 §11 P0-1에 따라 먼저 수행한 작업. **원인(구버전 컨테이너)은 막지 못하지만** 현재 코드의 방어 가드로 유지 가치가 있다.

- 브랜치: `main`(`bc294b4`)에서 `fix/bridge-queue-orphan-guard` 생성. **미배포, 미커밋.**
- 변경: `ParticipationBridge.drainCampaignQueue`에서 `RPOP null` + flag 없음일 때 바로 `deactivateCampaign`하던 것을 `deactivateIfQueueEmpty`로 교체.
  - 제거 직전 `LLEN(queueKey)` 재조회. `null` 또는 `> 0`이면 제거하지 않고 WARN `Deactivation skipped: queue not empty after null RPOP with active flag absent. campaignId, llen, instance`.
  - `== 0`일 때만 제거, INFO 로그에 `instance`(HOSTNAME → `InetAddress` → `unknown`) 추가.
- 테스트: `ParticipationBridgeDeactivationTest` 4건 신규.

| 케이스 | 기대 |
|---|---|
| flag 없음 + RPOP null + LLEN 0 | 제거 |
| flag 없음 + RPOP null + LLEN 1,300,000 | 제거 안 함 |
| flag 없음 + RPOP null + LLEN null | 제거 안 함 |
| flag 있음 + 큐 빔 | 제거 안 함 |

- 검증 (추적 파일만 있는 임시 worktree에서 실행):
  - 수정 전 코드: 가드 케이스 2건(LLEN 잔량, LLEN null) **실패** → 테스트가 기존 동작을 잡아냄.
  - 수정 후 코드: 추적 테스트 전체 통과 (`ParticipationBridgeDeactivationTest` 4, `QueueMetricsSchedulerTest` 3, `ParticipationServiceTest` 8, `DlqReplayPolicyServiceTest` 5, `RateLimitServiceTest` 4, `ParticipationEventConsumerTest` 2, `CampaignCoreApplicationTests` 1 skipped).
- 작업 트리에서 `gradlew test`는 **테스트 컴파일 실패**: 추적되지 않은 로컬 테스트 파일들(`integration/full/*`, `RedisQueueServiceTest`, `PollingControllerTest` 등)이 현재 코드에 없는 심볼을 참조. 로컬 `ParticipationBridgeTest.java`는 해시태그 없는 큐 키(`queue:campaign:1`)를 써서 현재 코드와 불일치. 건드리지 않음.

### 7.3 증거 수집 도구와 실행 중 발생한 문제

`ops/scripts/` (미커밋):

| 파일 | 역할 |
|---|---|
| `lib-ssm.sh` | 공용: terraform-mcp/ASG 인스턴스 조회, Redis 주소(SSM Parameter), `ssm_run`(SendCommand+대기), `ssm_fetch`(gzip+base64 20,000자 분할 전송) |
| `redis-watch-start.sh <id>` | terraform-mcp에서 `redis:7-alpine` 컨테이너로 1초마다 `SISMEMBER`/`EXISTS`/`LLEN`/`GET`/`SMEMBERS` 기록 (KST) |
| `redis-watch-fetch.sh [--stop] [dir]` | 기록 회수 + inSet/flag 변화 지점 요약 |
| `collect-app-logs.sh [dir]` | ASG 전 인스턴스(또는 `INSTANCE_IDS`) 로그 전체 + 부팅/컨테이너 시작 시각 + 핵심 로그 grep |
| `capture-boot-containers.sh <instance>` | 신규 인스턴스 부팅 시 현재 배포 이미지가 아닌 컨테이너의 메타·로그를 `/tmp/boot-capture/`에 6분간 저장 |

| 문제 | 원인 | 조치 |
|---|---|---|
| SSM 출력이 24,000자에서 잘림 | SSM `get-command-invocation` 제한 | `ssm_fetch`: 원격에서 gzip+base64 후 20,000자씩 분할 수신. 로컬 4만 줄 가짜 로그로 왕복 일치 검증 |
| zsh에서 `ssm_run:9: read-only variable: status` | zsh 예약 변수 `status` | 변수명 `inv_status`로 변경 |
| redis-watch 1차 시작 실패 `Name does not resolve` | SSM Parameter가 SecureString인데 `--with-decryption` 누락 | 옵션 추가 + 시작 시 `Could not connect` 검출하면 실패 처리 |
| k6 SSM 실행이 시간 초과 | SSM이 백그라운드 자식 종료를 기다림 | `setsid nohup ... &` 는 정상 실행됨을 확인, 이후 `send-command`만 하고 대기하지 않음 |
| 로그 수집이 재부팅 중 인스턴스에서 정지 | ASG 목록에 무응답 인스턴스 포함 | `INSTANCE_IDS` 환경변수로 대상 지정 가능하게 변경 |
| 캡처 대상 인스턴스 무응답 | 1차 캡처 루프(2초, 전체 로그)의 부하 기여 추정 | 10초 간격, 현재 이미지 제외, `--tail 3000`으로 경량화 |
| 구버전 SREM 직접 관찰 시도 | Redis `SADD active:campaigns 64` 실행이 권한 정책(Remote Shell Writes)으로 거부 | 미실행, 소거법으로 판정 |

### 7.4 접속 정보 (당시)

- Grafana: `http://43.203.236.50:3000` (terraform-mcp 공인 IP, 재기동 시 변경될 수 있음). 테스트 구간 17:45~19:20 KST.
- ALB: `http://alb-batch-kafka-api-1351817547.ap-northeast-2.elb.amazonaws.com`

### 7.5 원본 증거 위치 (`evidence/20260928/`, 저장소 미포함)

| 경로 | 내용 |
|---|---|
| `run1-c63/redis-watch.log` | 1차 Redis 1초 기록 1,197줄 |
| `run1-c63/app-i-{0478bca4,086e8332,08786efc}….log/.meta` | 1차 앱 로그 3대 (47.1 / 58.2 / 36.3 MB) |
| `run1-c63/k6-summary.txt` | 1차 k6 요약 |
| `run2-c64/redis-watch.log` | 2차 Redis 1초 기록 1,922줄 |
| `run2-c64/app-i-{0478bca4,08786efc}….log/.meta` | 2차 앱 로그 2대 (65.2 / 54.3 MB) |
| `run2-c64/boot-capture-i-0a0b7920/` | 구버전 컨테이너 메타·로그(`537ec3755dbe`, `a5f68e64ead7`), `_ps.txt` |
| `run2-c64/k6-summary.txt` | 2차 k6 요약 |

---

## 8. 커밋 계획 (미커밋, 2026-09-28 기준)

브랜치: `fix/bridge-queue-orphan-guard` (`main` `bc294b4`에서 분기). 아래 순서로 나눠 커밋한다.

| # | 커밋 메시지 (안) | 포함 파일 |
|---|---|---|
| 1 | `fix: Bridge가 큐 잔량이 있으면 active Set에서 제거하지 않도록 LLEN 재확인` | `app/campaign-core/src/main/java/io/eventdriven/campaign/application/bridge/ParticipationBridge.java` |
| 2 | `test: Bridge active Set 제거 가드 테스트 추가` | `app/campaign-core/src/test/java/io/eventdriven/campaign/application/bridge/ParticipationBridgeDeactivationTest.java` **(이 파일만)** |
| 3 | `chore(ops): 부하 테스트 증거 수집 스크립트 추가` | `ops/scripts/lib-ssm.sh`, `redis-watch-start.sh`, `redis-watch-fetch.sh`, `collect-app-logs.sh`, `capture-boot-containers.sh`, `.gitignore`(`evidence/` 한 줄) |
| 4 | `docs: 150만 재현 테스트와 active Set 제거 원인(AMI 구버전 컨테이너) 기록` | `docs/current/2026-09-28-150m-reproduction-set-removal-trace.md` |

커밋할 때 주의:

- `git add .` 금지. 같은 디렉터리에 커밋하면 안 되는 로컬 파일이 섞여 있다.
  - `app/campaign-core/src/test/java/.../bridge/ParticipationBridgeTest.java` — 사용자 로컬 파일(미추적, 해시태그 없는 구 키 사용). 2번 커밋에서 제외.
  - 그 외 미추적 테스트(`StockRecoverySchedulerTest`, `CampaignServiceTest`, `RedisQueueServiceTest` 등), `application-local-test.yml`, `src/test/resources/` — 이번 작업과 무관.
- `evidence/`는 `.gitignore` 처리됨 (원본 로그 수백 MB).
- 작업 트리에 이전부터 있던 변경(`docker-compose.test.yml` staged, `docs/blog/part5-…md`, `infra/.terraform.lock.hcl`)은 이번 커밋에서 제외.
- `docs/current/2026-09-19-150m-load-test-incident.md`, `2026-09-25-150m-retest-queue-residue-analysis.md`도 현재 미추적이다. 4번에 같이 넣을지는 사용자 결정.
- 1번은 원인(구버전 컨테이너)을 막지 못하는 방어 가드다(§6.2 P2). 원인 조치(AMI 재생성 / user-data 컨테이너 정리, §6.2 P0)는 별도 브랜치·PR로 진행한다.
