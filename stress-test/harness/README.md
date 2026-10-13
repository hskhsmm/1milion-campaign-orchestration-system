# 부하 테스트 하네스 — 실행 manifest와 로컬 k6 요약

이 단계는 **테스트를 실행하지 않는다**. 기존 `run-test.sh`를 바꾸기 전에 실행 조건을 한 파일로 고정하는 최소 계약이다. 생성된 manifest의 상태는 항상 `PREPARED`다. `PASS`를 뜻하지 않는다.

## 사용 예시

`config.json`에는 실행할 때 사용할 **실효값**을 기록한다. `run-test.sh`의 기본값을 사용했다면 그 기본값도 명시한다. 비밀번호, 토큰, 사용자 ID, 요청 원문은 넣지 않는다.

```json
{
  "environment": "local",
  "mode": "integrity",
  "parameters": {
    "campaign_id": 1,
    "total_requests": 100,
    "max_vus": 10,
    "duration_seconds": 60
  },
  "infrastructure": {
    "app_instance_count": 1,
    "app_instance_type": "local-docker",
    "kafka_partitions": 1
  }
}
```

```bash
python3 stress-test/harness/manifest.py --config /path/to/config.json
python3 -m unittest discover -s stress-test/harness/tests -v
```

출력은 `stress-test/harness/runs/<run-id>/manifest.json`에 저장되며 Git에서 제외된다. 운영 환경의 실제 배포 이미지·AMI·인스턴스 값은 확인 후에만 기입한다. 이 스크립트는 AWS에 접속하지 않고 `run-test.sh`도 호출하지 않는다.

## 이미 실행한 k6 결과 요약

k6 실행 시 `--summary-export /path/to/k6-summary.json`으로 저장한 JSON을 사용할 수 있다. `manifest.py`가 출력한 실행 디렉터리를 지정한다.

```bash
python3 stress-test/harness/report.py \
  --run-dir stress-test/harness/runs/<run-id> \
  --k6-summary /path/to/k6-summary.json
```

같은 디렉터리에 `report.json`을 **새로** 쓴다. 요청 원문과 사용자 ID는 복사하지 않고 필요한 집계값만 추출한다. `FAIL`은 확인된 접수 건수 불일치·드롭 등 명백한 실패를 뜻한다. 문제가 보이지 않아도 k6 전체 실행 집계만으로는 안정 구간 p95/p99, Redis Queue, Kafka lag, DB 신규 INSERT 및 정합성, E2E 지연을 확인할 수 없어 `INCONCLUSIVE`다. 이 단계에서는 절대 `PASS`를 만들지 않는다. `http_req_failed`는 전체 HTTP 요청 집계라 참여 요청의 5xx 건수로 해석하지 않는다.

다음 단계에서 기존 실행 절차와 연결하고 Queue·Kafka·DB 원본 증거를 같은 실행 ID에 모은 뒤, 동일 조건 비교와 최종 판정 규칙을 추가한다.
