import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';

// 커스텀 메트릭
const successCount = new Counter('participation_success');
const failCount = new Counter('participation_fail');

// 환경변수로부터 설정 읽기
const RATE = parseInt(__ENV.RATE) || 3000;           // 초당 요청 수
const DURATION = parseInt(__ENV.DURATION) || 10;     // 테스트 지속 시간 (초)
const MAX_VUS = parseInt(__ENV.MAX_VUS) || 5000;     // 최대 가상 사용자 수
const TOTAL_REQUESTS = parseInt(__ENV.TOTAL_REQUESTS) || 30000; // 총 요청 수
const PARTITIONS = parseInt(__ENV.PARTITIONS) || 3;  // Kafka 파티션 수

console.log(`🚀 Kafka 부하 테스트 설정: 정확히 ${TOTAL_REQUESTS}개 요청, 목표 ${RATE}/s, ${DURATION}s, ${PARTITIONS} 파티션`);

// 사전 생성된 userId 배열 (정확히 TOTAL_REQUESTS개)
const userIds = new SharedArray('userIds', function () {
  const arr = [];
  for (let i = 1; i <= TOTAL_REQUESTS; i++) {
    arr.push(i);
  }
  return arr;
});

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
    participation_success: ['count>9900'],    // 성공 건수 기준
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const CAMPAIGN_ID = __ENV.CAMPAIGN_ID || 1;

export default function () {
  // 전역 iteration 인덱스로 userId 결정 (VU 간 중복 없이 유니크)
  const userId = userIds[exec.scenario.iterationInTest];

  const payload = JSON.stringify({
    userId: userId,
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
    },
    timeout: '1200s', // 타임아웃 20분 (여유있게 설정)
  };

  // 선착순 참여 요청
  const response = http.post(
    `${BASE_URL}/api/campaigns/${CAMPAIGN_ID}/participation`,
    payload,
    params
  );

  // 응답 검증 (v2: 202 Accepted 반환)
  const isSuccess = check(response, {
    'status is 202': (r) => r.status === 202,
  });

  // 성공/실패 카운트
  if (response.status === 202) {
    successCount.add(1);
  } else {
    failCount.add(1);
  }

  // 응답 로그 (샘플링 - 100번째마다)
  if (exec.scenario.iterationInTest % 100 === 0) {
    console.log(`[Iteration ${exec.scenario.iterationInTest}] UserID: ${userId}, Status: ${response.status}`);
  }
}

// K6 기본 summary 사용 (handleSummary 제거하여 백엔드 파서와 호환)
