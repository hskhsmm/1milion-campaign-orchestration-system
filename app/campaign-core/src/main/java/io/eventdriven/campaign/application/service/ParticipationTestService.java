package io.eventdriven.campaign.application.service;

import io.eventdriven.campaign.application.event.ParticipationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 부하 테스트용 서비스
 * - 대량의 Kafka 메시지를 발행하여 선착순 시스템 동작 테스트
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParticipationTestService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JsonMapper jsonMapper;

    private static final String TOPIC = "campaign-participation-topic";

    // 테스트용 고유 사용자 ID를 만들기 위한 AtomicLong
    // System.currentTimeMillis()로 초기화하여 매번 다른 ID 생성
    private final AtomicLong userIdCounter = new AtomicLong(System.currentTimeMillis());

    /**
     * 대량 참여 시뮬레이션
     *
     * @param campaignId 캠페인 ID
     * @param count 발행할 메시지 수
     */
    @Async  // 비동기 실행: HTTP 응답이 지연되지 않도록 함
    public void simulate(Long campaignId, int count) {
        log.info("📊 시뮬레이션 시작 - 캠페인 ID: {}, 총 {:,}건", campaignId, count);

        // 요청 규모에 따라 백프레셔 간격 동적 조정
        int backpressureInterval = calculateBackpressureInterval(count);
        log.info("💤 백프레셔 설정 - {}건마다 500ms 대기", backpressureInterval);

        long startTime = System.currentTimeMillis();
        int successCount = 0;
        int failCount = 0;

        for (int i = 0; i < count; i++) {
            try {
                // 각 요청마다 고유한 사용자 ID 생성
                long userId = userIdCounter.getAndIncrement();

                // ParticipationEvent 객체 생성
                ParticipationEvent event = new ParticipationEvent(campaignId, userId);

                // JSON으로 직렬화
                String message = jsonMapper.writeValueAsString(event);

                // Kafka에 메시지 발행 (비동기)
                kafkaTemplate.send(TOPIC, String.valueOf(campaignId), message);

                successCount++;

                // 진행 상황 로그 및 백프레셔 (동적 간격)
                if ((i + 1) % backpressureInterval == 0) {
                    log.info("📤 {:,} / {:,} 건 발행 완료 ({:.1f}%)",
                            (i + 1), count, ((i + 1) * 100.0 / count));

                    // 백프레셔: Kafka 버퍼가 숨 돌릴 시간 제공
                    try {
                        Thread.sleep(500);  // 500ms 대기 (Consumer 처리 시간 확보)
                        log.debug("💤 백프레셔: 500ms 대기 (버퍼 안정화)");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("⚠️ 백프레셔 대기 중 인터럽트 발생");
                    }
                }

            } catch (Exception e) {
                failCount++;
                log.error("❌ 테스트 메시지 발행 실패 (userId: {})", userIdCounter.get(), e);
            }
        }

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        double throughput = (count * 1000.0) / duration;  // 초당 처리량

        log.info("✅ 시뮬레이션 완료!");
        log.info("   - 총 요청: {:,}건", count);
        log.info("   - 성공: {:,}건", successCount);
        log.info("   - 실패: {:,}건", failCount);
        log.info("   - 소요 시간: {:,}ms ({:.2f}초)", duration, duration / 1000.0);
        log.info("   - 처리량: {:.0f} 건/초", throughput);
    }

    /**
     * 요청 규모에 따라 백프레셔 간격 동적 계산
     *
     * 소량 요청: 촘촘한 백프레셔 (안정성 중시)
     * 대량 요청: 넓은 백프레셔 (처리량 중시)
     *
     * @param totalRequests 총 요청 수
     * @return 백프레셔 간격 (건수)
     */
    private int calculateBackpressureInterval(int totalRequests) {
        if (totalRequests <= 10000) {
            return 500;  // 500건마다 (안정성 최우선)
        } else if (totalRequests <= 30000) {
            return 700;  // 700건마다 (안정성 유지)
        } else if (totalRequests <= 70000) {
            return 1000;  // 1,000건마다 (70k 최적화)
        } else if (totalRequests <= 100000) {
            return 1500;  // 1,500건마다 (100k 최적화)
        } else if (totalRequests <= 200000) {
            return 3000;  // 3,000건마다 (대용량 처리)
        } else {
            return 5000;  // 5,000건마다 (초대용량 처리)
        }
    }
}
