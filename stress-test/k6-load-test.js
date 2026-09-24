import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';

// 커스텀 메트릭
const successCount = new Counter('participation_success');
const failCount = new Counter('participation_fail');

// 환경변수로부터 설정 읽기
const DURATION = parseInt(__ENV.DURATION) || 10;     // 테스트 지속 시간 (초)
const MAX_VUS = parseInt(__ENV.MAX_VUS) || 5000;     // 최대 가상 사용자 수
const TOTAL_REQUESTS = parseInt(__ENV.TOTAL_REQUESTS) || 30000; // 총 요청 수
const USER_ID_OFFSET = parseInt(__ENV.USER_ID_OFFSET || '0', 10);
const LOG_EVERY = parseInt(__ENV.LOG_EVERY || '10000', 10);
const REQUEST_TIMEOUT = __ENV.REQUEST_TIMEOUT || '30s';

console.log(`[k6] total=${TOTAL_REQUESTS}, maxVUs=${MAX_VUS}, maxDuration=${DURATION * 2}s`);

// 테스트 설정 - shared-iterations로 정확한 요청 수 보장
export const options = {
  scenarios: {
    exact_requests: {
      executor: 'shared-iterations',
      vus: MAX_VUS,              // 동시 실행 VU 수
      iterations: TOTAL_REQUESTS, // 정확히 이 수만큼만 실행
      maxDuration: `${DURATION * 2}s`, // 최대 허용 시간 (duration의 2배 여유)
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],           // 에러율 1% 미만
    http_req_duration: ['p(95)<1000'],        // p95 1초 미만
    participation_success: [`count==${TOTAL_REQUESTS}`],
    participation_fail: ['count==0'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://alb-batch-kafka-api-1351817547.ap-northeast-2.elb.amazonaws.com';
const CAMPAIGN_ID = __ENV.CAMPAIGN_ID || 1;

export default function () {
  // 전역 iteration 인덱스로 userId 결정 (VU 간 중복 없이 유니크)
  const userId = USER_ID_OFFSET + exec.scenario.iterationInTest + 1;

  const payload = JSON.stringify({
    userId: userId,
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
    },
    timeout: REQUEST_TIMEOUT,
  };

  // 선착순 참여 요청
  const response = http.post(
    `${BASE_URL}/api/campaigns/${CAMPAIGN_ID}/participation`,
    payload,
    params
  );

  // 응답 검증 (v2: 202 Accepted 반환)
  check(response, {
    'status is 202': (r) => r.status === 202,
  });

  // 성공/실패 카운트
  if (response.status === 202) {
    successCount.add(1);
  } else {
    failCount.add(1);
  }

  // 과도한 stdout I/O가 부하 발생기 처리량을 왜곡하지 않도록 드물게 샘플링한다.
  if (LOG_EVERY > 0 && exec.scenario.iterationInTest % LOG_EVERY === 0) {
    console.log(`[Iteration ${exec.scenario.iterationInTest}] UserID: ${userId}, Status: ${response.status}`);
  }
}

// K6 기본 summary 사용 (handleSummary 제거하여 백엔드 파서와 호환)
