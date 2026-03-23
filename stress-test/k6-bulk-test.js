import http from 'k6/http';
import { check, sleep } from 'k6';

/**
 * k6 부하 테스트 API 검증
 *
 * 새로 만든 /api/admin/test/participate-bulk API를 테스트합니다.
 * 이 API는 한 번 호출 시 count만큼의 Kafka 메시지를 발행합니다.
 *
 * 실행 방법:
 * ./k6.exe run k6-bulk-test.js
 */

const BASE_URL = 'http://localhost:8080';

export const options = {
  vus: 1,           // 가상 사용자 1명 (API 자체가 대량 메시지 발행하므로)
  iterations: 1,    // 1회만 실행
};

export default function () {
  console.log('========================================');
  console.log('🚀 부하 테스트 API 호출');
  console.log('========================================');

  const payload = JSON.stringify({
    count: 5000,      // 5000건의 참여 요청 시뮬레이션
    campaignId: 1
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
    },
  };

  console.log(`📤 요청: POST ${BASE_URL}/api/admin/test/participate-bulk`);
  console.log(`   Body: ${payload}`);

  const startTime = Date.now();
  const response = http.post(
    `${BASE_URL}/api/admin/test/participate-bulk`,
    payload,
    params
  );
  const duration = Date.now() - startTime;

  console.log(`\n📨 응답 수신 (${duration}ms):`);
  console.log(`   Status: ${response.status}`);
  console.log(`   Body: ${response.body}`);

  // 응답 검증
  const checks = check(response, {
    'API 응답 성공 (200)': (r) => r.status === 200,
    'ApiResponse.success = true': (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.success === true;
      } catch (e) {
        return false;
      }
    },
    '응답 시간 < 1초': (r) => duration < 1000,
    'data.requestCount 확인': (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.data.requestCount === 5000;
      } catch (e) {
        return false;
      }
    },
  });

  if (checks) {
    console.log('\n✅ 모든 검증 통과!');
    console.log('\n📊 다음 단계:');
    console.log('   1. 애플리케이션 로그에서 진행 상황 확인');
    console.log('   2. Kafka UI에서 메시지 확인 (http://localhost:8081)');
    console.log('   3. 5~10초 후 Consumer 처리 완료 대기');
    console.log('   4. 통계 조회:');
    console.log('      GET http://localhost:8080/api/admin/campaigns');
    console.log('========================================');
  } else {
    console.error('\n❌ 검증 실패!');
  }
}

export function handleSummary(data) {
  console.log('\n========================================');
  console.log('📈 테스트 요약');
  console.log('========================================');
  console.log(`총 HTTP 요청: ${data.metrics.http_reqs.values.count}건`);
  console.log(`평균 응답 시간: ${data.metrics.http_req_duration.values.avg.toFixed(2)}ms`);
  console.log(`실패율: ${(data.metrics.http_req_failed.values.rate * 100).toFixed(2)}%`);
  console.log('========================================\n');

  return {
    'stdout': '',  // 기본 요약 출력 생략
  };
}
