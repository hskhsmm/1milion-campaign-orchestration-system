import http from 'k6/http';
import { check, sleep } from 'k6';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.1/index.js';

/**
 * k6 선착순 정합성 검증 테스트
 *
 * 실행 방법:
 * 1. 캠페인 생성 (재고 50개)
 * 2. k6 run k6-verify-test.js
 * 3. 배치 실행 (집계)
 * 4. DB 확인 → 정확히 50개 성공했는지 검증
 */

const BASE_URL = __ENV.BASE_URL || 'http://alb-batch-kafka-api-1351817547.ap-northeast-2.elb.amazonaws.com';

export const options = {
  stages: [
    { duration: '1s', target: 100 }, // 1초간 100명으로 증가
    { duration: '2s', target: 0 },   // 2초간 0명으로 감소 (정리)
  ],
  thresholds: {
    http_req_duration: ['p(95)<1000'], // 95%가 1초 이내
  },
};

export function setup() {
  console.log('========================================');
  console.log('🚀 선착순 부하 테스트 시작');
  console.log('========================================');
  console.log('테스트 시나리오:');
  console.log('  - 동시 요청: 100명');
  console.log('  - 예상 재고: 50개');
  console.log('  - 예상 결과: 성공 50건, 나머지는 재고 부족');
  console.log('========================================\n');

  // 관리자 API로 캠페인 목록 조회 (테스트용)
  const campaignsRes = http.get(`${BASE_URL}/api/admin/campaigns`);

  if (campaignsRes.status === 200) {
    try {
      const body = JSON.parse(campaignsRes.body);
      const campaigns = body.data || [];

      if (campaigns.length > 0) {
        const campaign = campaigns[0];
        console.log(`📋 테스트 대상 캠페인:`);
        console.log(`   ID: ${campaign.id}`);
        console.log(`   이름: ${campaign.name}`);
        console.log(`   현재 재고: ${campaign.currentStock}/${campaign.totalStock}`);
        console.log('========================================\n');

        return { campaignId: campaign.id };
      }
    } catch (err) {
      console.error('캠페인 정보 파싱 실패:', err);
    }
  }

  console.warn('⚠️  캠페인을 찾을 수 없습니다. 기본 ID=1 사용');
  return { campaignId: 1 };
}

export default function (data) {
  const campaignId = data.campaignId;
  const userId = 10000 + __VU; // 10001~10100 (고유한 사용자 ID)

  const payload = JSON.stringify({
    userId: userId,
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
    },
  };

  const response = http.post(
    `${BASE_URL}/api/campaigns/${campaignId}/participation`,
    payload,
    params
  );

  check(response, {
    'API 응답 성공 (200)': (r) => r.status === 200,
    'ApiResponse 형식': (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.success !== undefined;
      } catch (e) {
        return false;
      }
    },
  });

  // 10명마다 로그 출력
  if (__VU % 10 === 0) {
    console.log(`[사용자 ${userId}] 요청 완료 - Status: ${response.status}`);
  }
}

export function teardown(data) {
  console.log('\n========================================');
  console.log('✅ 부하 테스트 완료');
  console.log('========================================');
  console.log('다음 단계:');
  console.log('  1. Kafka Consumer 처리 대기 (5~10초)');
  console.log('  2. 배치 집계 실행:');
  console.log(`     POST ${BASE_URL}/api/admin/batch/aggregate?date=2025-12-28`);
  console.log('  3. 통계 확인:');
  console.log(`     GET ${BASE_URL}/api/admin/stats/daily?date=2025-12-28`);
  console.log('  4. DB 직접 확인:');
  console.log('     SELECT status, COUNT(*) FROM participation_history GROUP BY status;');
  console.log('========================================');
  console.log('예상 결과:');
  console.log('  - SUCCESS: 50건');
  console.log('  - FAIL: 50건');
  console.log('  - campaign.current_stock = 0');
  console.log('========================================\n');
}

export function handleSummary(data) {
  return {
    'stdout': textSummary(data, { indent: '  ', enableColors: true }),
  };
}
