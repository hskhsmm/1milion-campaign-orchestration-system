/**
 * 시나리오 1: TPS 측정 테스트
 *
 * 목적: 시스템이 초당 몇 건의 참여 요청을 처리할 수 있는지 측정
 * 방식: ramping-arrival-rate로 목표 RPS까지 램프업 후 안정 구간 측정
 *
 * 사전 조건:
 *   Kafka 토픽이 존재해야 합니다 (Bridge/Consumer 동작):
 *   docker exec kafka kafka-topics --bootstrap-server kafka:29092 \
 *     --create --if-not-exists --topic campaign-participation-topic \
 *     --partitions 1 --replication-factor 1
 *
 * 실행 (도커):
 *   docker compose --profile k6 run --rm k6 run /scripts/k6-tps-test.js
 *
 * 실행 (직접):
 *   k6 run stress-test/k6-tps-test.js
 *
 * 환경변수 오버라이드:
 *   docker compose --profile k6 run --rm \
 *     -e TARGET_RPS=1000 \
 *     k6 run /scripts/k6-tps-test.js
 */

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

// 커스텀 메트릭
const acceptedCount = new Counter('participation_accepted_202');
const rejectedCount = new Counter('participation_rejected_400');
const rateLimitCount = new Counter('participation_rate_limited_429');
const errorCount   = new Counter('participation_error');
const apiDuration  = new Trend('api_duration_ms', true);

// 환경변수
const BASE_URL = __ENV.BASE_URL || 'http://alb-batch-kafka-api-1351817547.ap-northeast-2.elb.amazonaws.com';
const TARGET_RPS = parseInt(__ENV.TARGET_RPS || '300', 10);
const START_RPS = parseInt(__ENV.START_RPS || '50', 10);
const WARMUP_SECONDS = parseInt(__ENV.WARMUP_SECONDS || '10', 10);
const STEADY_SECONDS = parseInt(__ENV.STEADY_SECONDS || '30', 10);
const COOLDOWN_SECONDS = parseInt(__ENV.COOLDOWN_SECONDS || '5', 10);
const PRE_ALLOCATED_VUS = parseInt(
  __ENV.PRE_ALLOCATED_VUS || String(Math.max(200, Math.ceil(TARGET_RPS * 0.6))),
  10
);
const MAX_VUS = parseInt(
  __ENV.MAX_VUS || String(Math.max(2000, TARGET_RPS * 2)),
  10
);
const P95_MS = parseInt(__ENV.P95_MS || '10000', 10);
const MAX_FAIL_RATE = parseFloat(__ENV.MAX_FAIL_RATE || '0.05');

export const options = {
  scenarios: {
    tps_test: {
      executor: 'ramping-arrival-rate',
      startRate: START_RPS,
      timeUnit: '1s',
      preAllocatedVUs: PRE_ALLOCATED_VUS,
      maxVUs: MAX_VUS,
      stages: [
        { target: TARGET_RPS, duration: `${WARMUP_SECONDS}s` }, // 워밍업: 목표 RPS까지 램프업
        { target: TARGET_RPS, duration: `${STEADY_SECONDS}s` }, // 안정 구간: 실제 TPS 측정
        { target: 0, duration: `${COOLDOWN_SECONDS}s` }, // 종료
      ],
    },
  },
  thresholds: {
    http_req_duration: [`p(95)<${P95_MS}`], // 기본 10초, 필요시 env로 조정
    http_req_failed: [`rate<${MAX_FAIL_RATE}`],
    participation_accepted_202: ['count>0'],
  },
};

export function setup() {
  console.log(
    `[TPS setup] target=${TARGET_RPS}/s start=${START_RPS}/s warmup=${WARMUP_SECONDS}s steady=${STEADY_SECONDS}s preVUs=${PRE_ALLOCATED_VUS} maxVUs=${MAX_VUS}`
  );

  // 재고 소진 없이 TPS만 측정 — 충분히 큰 stock 설정
  const res = http.post(
    `${BASE_URL}/api/admin/campaigns`,
    JSON.stringify({ name: `TPS-Test-${Date.now()}`, totalStock: 9999999 }),
    { headers: { 'Content-Type': 'application/json' } }
  );

  if (res.status !== 200 && res.status !== 201) {
    throw new Error(`[Setup] 캠페인 생성 실패: ${res.status} ${res.body}`);
  }

  const campaignId = JSON.parse(res.body).data.id;
  console.log(`[Setup] 캠페인 생성 완료. ID=${campaignId}, stock=9,999,999, 목표 RPS=${TARGET_RPS}`);
  return { campaignId };
}

export default function (data) {
  const campaignId = data.campaignId;
  // __VU(1~maxVUs)와 __ITER(VU별 0~) 조합으로 유니크 userId 생성
  const userId = __VU * 100000 + __ITER + 1;

  const start = Date.now();
  const res = http.post(
    `${BASE_URL}/api/campaigns/${campaignId}/participation`,
    JSON.stringify({ userId }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  apiDuration.add(Date.now() - start);

  check(res, { '202 Accepted': (r) => r.status === 202 });

  if      (res.status === 202) acceptedCount.add(1);
  else if (res.status === 400) rejectedCount.add(1);
  else if (res.status === 429) rateLimitCount.add(1);
  else {
    errorCount.add(1);
    console.error(`[VU ${__VU}] 예상치 못한 응답: ${res.status} — ${res.body}`);
  }
}

export function teardown(data) {
  const campaignId = data.campaignId;
  const res = http.get(`${BASE_URL}/api/campaigns/${campaignId}/status`);

  if (res.status === 200) {
    const d = JSON.parse(res.body).data;
    console.log(`\n[TPS 측정 결과 요약]`);
    console.log(`  캠페인 ID : ${d.campaignId}`);
    console.log(`  총 재고   : ${d.totalStock}`);
    console.log(`  현재 재고 : ${d.currentStock}`);
    console.log(`  목표 RPS  : ${TARGET_RPS}/s`);
    console.log(`  → 상세 지표는 k6 summary 참고 (api_duration_ms, participation_accepted_202)`);
  }
}
