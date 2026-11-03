# Bridge 관측 보완: 로컬 검증

> 작업 브랜치: perf/bridge-drain-observability. 기반 origin/main b2ad271.
> 상태: 로컬 계측·검증 준비 완료. AWS 기동·배포·부하 실행 없음. 실환경 배포 전 검토용 변경.

## 가설과 변경

150만 r4 보고서의 drain 603→911초, 부하 후 Bridge 약 1,970→1,000건/s 저하를 조사하기 위한 계측이다. 순차 claim과 Redis 대기는 후보일 뿐 원인 확정은 아니다.

Redis claim/ACK/reclaim의 클라이언트 호출 시간·횟수·예외와 선점 성공/결과 없음을 구분했다. Kafka send 동기 대기와 성공/실패 응답까지의 시간을 분리했다. 고정 operation/result 태그를 사용하며 관측용 외부 호출은 추가하지 않는다.

Grafana에 진단 패널 4개를 추가하고 수집용 PromQL 목록을 준비했다. 기존 Lua·claim 상한·복구 주기·Kafka 설정·ACK 정책은 바꾸지 않았다.

## 실행한 검증

Java 25, Gradle offline, 격리된 localhost Docker Redis 7.2. Redis를 지정한 상태에서 전체 테스트 실행: **86건, 실패 0·오류 0·건너뜀 11, 실제 75건 통과.** 기존 DB 연동 10건과 원래 skip 1건은 이번 실행에서 미실행이다.

- MockClock으로 Redis 5ms가 클라이언트 timer에 기록되는 것을 검증했다.
- Kafka send 동기 10ms와 응답까지 30ms를 구분했다. 미래 응답 전 ACK 금지·실패 시 claim 유지·ACK 실행기 거부의 기존 검증을 유지했다.
- Redis 예외의 원형 전파·호출 1회·timer/error count, null 선점과 성공 카운터 구분을 확인했다.
- 실제 Prometheus registry의 fixed bucket·operation tag·counter 노출을 확인했다.
- 실제 Redis에서 claim → 만료 복구 → 재선점 → 오래된 ACK 거부 → 새 ACK 성공, 최종 Queue/ZSET 0을 확인했다. 계측 횟수는 claim 3 / ACK 2 / reclaim 1이었다.
- Grafana JSON과 조회 목록의 구조·일치 여부, git diff 공백 검사를 수행했다.

## 하네스 확인 결과

원격 feat/evidence-harness(2c9f9e4)의 collect.sh에 Queue Gauge sum이 남아 있음을 읽기 전용으로 확인했다. main의 Grafana/MCP 조회는 이미 max by(campaignId)였다. 별도 하네스 브랜치·WIP는 병합하지 않았으며 #101의 수집기 수정·main 통합은 남아 있다.

## 미검증·다음 단계

계측 오버헤드·실제 Redis 네트워크 지연·원인·처리량·API/E2E p95/p99 개선은 측정하지 않았다. 로컬 단일 Redis 시험은 Redis Cluster·Kafka 전체 경로·AWS 시험을 대신하지 않는다. Grafana 실화면과 실제 Prometheus 스크래핑·query_range도 미실행이다.

다음은 AWS 사용이 가능할 때 버전·잔류 데이터·고정 자원 조건을 확인하고 기준선에서 Redis/ACK/send 대기를 분해하는 것이다. 최적화나 서버 증설을 선결론으로 두지 않는다. 상세 해석과 명령은 [운영 가이드](../runbooks/bridge-observability.md), 조회는 [PromQL 목록](../../stress-test/bridge-observation-queries.tsv)을 따른다.
