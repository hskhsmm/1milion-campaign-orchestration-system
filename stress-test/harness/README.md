# 부하 테스트 하네스 — 1단계: 실행 manifest

이 단계는 **테스트를 실행하지 않는다**. 기존 `run-test.sh`를 바꾸기 전에 실행 조건을 한 파일로 고정하는 최소 계약이다. 실제 테스트 결과, AWS 상태, DB 정합성은 아직 수집하지 않으므로 생성된 manifest의 상태는 항상 `PREPARED`다. `PASS`를 뜻하지 않는다.

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

다음 단계에서 이 manifest를 기존 k6 실행과 연결하고, k6 원본 요약 및 Queue·Kafka·DB 증거를 같은 실행 ID에 모은 뒤 `PASS`/`FAIL`/`INCONCLUSIVE` 판정을 추가한다. 그전까지 이 파일만으로 성능이나 정합성을 주장하지 않는다.
