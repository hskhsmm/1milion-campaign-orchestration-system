package io.eventdriven.campaign.application.bridge;

import io.eventdriven.campaign.application.service.SlackNotificationService;
import io.eventdriven.campaign.config.KafkaConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis Queue → Kafka 유량 조절 브릿지 (v2)
 *
 * 역할: API에서 LPUSH된 메시지를 RPOP 후 Kafka로 발행.
 * - active:campaigns Set 순회 → 캠페인별 큐 드레인
 * - MAX_RETRY 3회 + exponential backoff 후 실패 시 DLQ + Slack 알림
 * - RPOP 실패 시 LPUSH 재적재 금지 (Queue 순서 뒤섞임 방지)
 * - 파티션 키: campaignId (동일 캠페인 메시지는 같은 파티션으로 라우팅)
 *
 * 주의: @EnableScheduling이 CampaignCoreApplication에 있어야 동작.
 */
@Slf4j
@Component
// @RequiredArgsConstructor 미사용 이유:
// Timer는 Spring Bean이 아닌 직접 생성 객체(Timer.builder().register())이므로
// Lombok이 생성자 파라미터로 주입할 수 없음.
// MeterRegistry(Bean)를 먼저 주입받은 뒤 생성자 안에서 Timer를 직접 만들어야 하므로
// 생성자를 수동으로 작성함.
public class ParticipationBridge {

    private final RedisTemplate<String, String> redisTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final SlackNotificationService slackNotificationService;
    private final MeterRegistry meterRegistry;  // Spring Bean → 생성자 주입

    private static final String ACTIVE_CAMPAIGNS_KEY = "active:campaigns";
    private static final String QUEUE_KEY_PREFIX = "queue:campaign:";
    private static final String DLQ_TOPIC = "campaign-participation-topic.dlq";
    private static final int MAX_RETRY = 3;

    // campaignId별 Counter 캐시
    // campaignId는 런타임에 결정되므로 생성자에서 미리 만들 수 없음 , 첫 발행 시 lazily 생성 후 재사용
    private final Map<Long, Counter> publishedCounters = new ConcurrentHashMap<>();

    // Timer는 Bean이 아닌 직접 생성 객체, 생성자에서 MeterRegistry를 받아 초기화
    private final Timer drainTimer;

    @Value("${bridge.batch-size:500}")
    private int batchSize;

    public ParticipationBridge(RedisTemplate<String, String> redisTemplate,
                               KafkaTemplate<String, String> kafkaTemplate,
                               SlackNotificationService slackNotificationService,
                               MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.slackNotificationService = slackNotificationService;
        this.meterRegistry = meterRegistry;  // 1. Bean 주입 완료
        this.drainTimer = Timer.builder("bridge.drain.duration")
                .description("drainQueues() 실행 소요시간")
                .register(meterRegistry);    // 2. 주입된 MeterRegistry로 Timer 등록
    }

    /**
     * 100ms마다 실행 (fixedDelay: 이전 실행 완료 후 100ms 대기)
     * active:campaigns Set에 등록된 캠페인별로 큐 드레인
     */
    @Scheduled(fixedDelay = 100)
    public void drainQueues() {
        drainTimer.record(() -> {
            Set<String> campaignIds = redisTemplate.opsForSet().members(ACTIVE_CAMPAIGNS_KEY);
            if (campaignIds == null || campaignIds.isEmpty()) {
                return;
            }

            for (String campaignIdStr : campaignIds) {
                try {
                    drainCampaignQueue(Long.parseLong(campaignIdStr));
                } catch (Exception e) {
                    log.error("캠페인 큐 드레인 중 예외 발생. campaignId={}", campaignIdStr, e);
                }
            }
        });
    }

    /**
     * 단일 캠페인 큐 드레인
     * batchSize만큼 RPOP → Kafka 발행. 큐가 비면 즉시 종료.
     */
    private void drainCampaignQueue(Long campaignId) {
        String queueKey = QUEUE_KEY_PREFIX + campaignId;

        for (int i = 0; i < batchSize; i++) {
            String message = redisTemplate.opsForList().rightPop(queueKey);
            if (message == null) {
                break; // 큐 소진 → 다음 캠페인으로
            }
            publishWithRetry(campaignId, message);
        }
    }

    /**
     * Kafka 발행 (MAX_RETRY 3회 + exponential backoff)
     * 최종 실패 시 DLQ 전송 + Slack 알림.
     * 실패 메시지를 Redis Queue에 재적재하지 않음 (순서 보장 우선).
     */
    private void publishWithRetry(Long campaignId, String message) {
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                kafkaTemplate.send(KafkaConfig.TOPIC_NAME, String.valueOf(campaignId), message);
                // Kafka 발행 성공 카운터 — campaignId 태그로 캠페인별 구분
                publishedCounters.computeIfAbsent(campaignId, id ->
                        Counter.builder("bridge.messages.published")
                                .description("Kafka 발행 성공 건수")
                                .tag("campaignId", String.valueOf(id))
                                .register(meterRegistry)
                ).increment();
                return; // 성공
            } catch (Exception e) {
                log.warn("Kafka 발행 실패 (시도 {}/{}). campaignId={}", attempt, MAX_RETRY, campaignId, e);

                if (attempt < MAX_RETRY) {
                    long backoffMs = (long) Math.pow(2, attempt) * 100L; // 200ms → 400ms
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.error("Bridge 스레드 인터럽트. campaignId={}", campaignId);
                        sendToDlqWithSlack(campaignId, message, "THREAD_INTERRUPTED");
                        return;
                    }
                }
            }
        }

        // MAX_RETRY 모두 소진
        log.error("Bridge MAX_RETRY({}) 초과. DLQ 전송. campaignId={}", MAX_RETRY, campaignId);
        sendToDlqWithSlack(campaignId, message, "MAX_RETRY_EXCEEDED");
    }

    /**
     * DLQ 전송 + Slack 알림
     * DLQ 전송 자체 실패 시 로그로만 기록 (무한 루프 방지)
     */
    private void sendToDlqWithSlack(Long campaignId, String message, String errorReason) {
        try {
            kafkaTemplate.send(DLQ_TOPIC, String.valueOf(campaignId), message);
            log.info("Bridge DLQ 전송 완료. campaignId={}, reason={}", campaignId, errorReason);
        } catch (Exception e) {
            log.error("CRITICAL: Bridge DLQ 전송도 실패! campaignId={}, message={}", campaignId, message, e);
        }

        slackNotificationService.sendDlqAlert(
                "Bridge Produce " + errorReason,
                "campaignId=" + campaignId
        );
    }
}
