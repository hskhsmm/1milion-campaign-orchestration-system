# 부하 테스트 증거 판정기

이 도구는 기존 [테스트 가이드](../TEST_GUIDE.md)의 증거 디렉터리를 읽어 `verdict.json`과 `verdict.md`를 만든다. Python 표준 라이브러리만 사용하고 AWS API 호출이나 부하 실행이 없다. 실행 중인 시험의 통합 수집은 별도 단계다.

```bash
python3 stress-test/harness/verdict.py evidence/<RUN_ID>
python3 -m unittest discover -s stress-test/harness/tests
```

판정기는 `feat/evidence-harness`의 기존 구현을 바탕으로 현재 Redis 감시 로그와 발행 증거를 지원한다.

- 최종 `integrity`는 k6 202·상태 API·DB 직접 조회에 더해 캠페인별 Kafka 발행 건수를 대조한다. `bridge-published-final.json`은 배출이 끝난 시점에 캠페인 ID로 좁힌 `sum(bridge_messages_published_total{campaignId="…"})`의 Prometheus instant query 응답이다. 실행 중 카운터 재시작이 있었으면 해당 값의 완전성을 확인한다.
- 최종 Queue LLEN과 in-flight ZCARD는 `redis-watch.log`의 마지막 상태를 읽고, Kafka lag는 `kafka-lag-final.json`에서 확인한다. 과거 로그에 in-flight 값이 빠졌거나 발행 응답이 비어 있으면 최종 정합성은 `INCONCLUSIVE`다.
- `integrity`의 클라이언트 p95 1초 기준 결과는 정합성 판정과 함께 별도 항목으로 남긴다. k6 기본 threshold의 실패 플래그를 사용한다.
- 비교에 필요한 사전 점검·증설 기록 등은 실행 시점의 근거를 사용한다. 과거 결과의 누락된 값을 임의로 채우지 않는다.
