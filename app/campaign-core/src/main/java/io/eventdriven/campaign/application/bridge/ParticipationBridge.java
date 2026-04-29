package io.eventdriven.campaign.application.bridge;

import io.eventdriven.campaign.application.event.DlqEventPayload;
import io.eventdriven.campaign.application.event.ParticipationEvent;
import io.eventdriven.campaign.application.service.DlqMessageService;
import io.eventdriven.campaign.application.service.RedisStockService;
import io.eventdriven.campaign.application.service.SlackNotificationService;
import io.eventdriven.campaign.config.KafkaConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class ParticipationBridge {

    private static final String ACTIVE_CAMPAIGNS_KEY = "active:campaigns";
    private static final String QUEUE_KEY_PREFIX = "queue:campaign:";
    private static final int MAX_RETRY = 3;

    private final RedisTemplate<String, String> redisTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final RedisStockService redisStockService;
    private final SlackNotificationService slackNotificationService;
    private final MeterRegistry meterRegistry;
    private final JsonMapper jsonMapper;
    private final DlqMessageService dlqMessageService;
    private final Timer drainTimer;
    private final Map<Long, Counter> publishedCounters = new ConcurrentHashMap<>();

    public ParticipationBridge(
            RedisTemplate<String, String> redisTemplate,
            KafkaTemplate<String, String> kafkaTemplate,
            RedisStockService redisStockService,
            SlackNotificationService slackNotificationService,
            MeterRegistry meterRegistry,
            JsonMapper jsonMapper,
            DlqMessageService dlqMessageService
    ) {
        this.redisTemplate = redisTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.redisStockService = redisStockService;
        this.slackNotificationService = slackNotificationService;
        this.meterRegistry = meterRegistry;
        this.jsonMapper = jsonMapper;
        this.dlqMessageService = dlqMessageService;
        this.drainTimer = Timer.builder("bridge.drain.duration")
                .description("Time spent draining Redis queues into Kafka")
                .register(meterRegistry);
    }

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
                    log.error("Failed to drain campaign queue. campaignId={}", campaignIdStr, e);
                }
            }
        });
    }

    private void drainCampaignQueue(Long campaignId) {
        String queueKey = QUEUE_KEY_PREFIX + campaignId;
        Long queueSize = redisTemplate.opsForList().size(queueKey);
        int dynamicBatchSize = resolveBatchSize(queueSize);

        for (int i = 0; i < dynamicBatchSize; i++) {
            String message = redisTemplate.opsForList().rightPop(queueKey);
            if (message == null) {
                if (!redisStockService.isActive(campaignId)) {
                    redisStockService.deactivateCampaign(campaignId);
                    log.info("Campaign drained and deactivated. campaignId={}", campaignId);
                }
                break;
            }
            publishWithRetry(campaignId, message);
        }
    }

    private int resolveBatchSize(Long queueSize) {
        if (queueSize == null || queueSize < 10_000) {
            return 500;
        }
        if (queueSize < 100_000) {
            return 1_000;
        }
        return 2_000;
    }

    private void publishWithRetry(Long campaignId, String message) {
        String partitionKey = extractUserIdKey(message, campaignId);
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                kafkaTemplate.send(KafkaConfig.TOPIC_NAME, partitionKey, message);
                publishedCounters.computeIfAbsent(campaignId, id ->
                        Counter.builder("bridge.messages.published")
                                .description("Kafka messages published by bridge")
                                .tag("campaignId", String.valueOf(id))
                                .register(meterRegistry)
                ).increment();
                return;
            } catch (Exception e) {
                log.warn("Kafka publish failed. attempt={}/{}, campaignId={}", attempt, MAX_RETRY, campaignId, e);
                if (attempt < MAX_RETRY) {
                    long backoffMs = (long) Math.pow(2, attempt) * 100L;
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        sendToDlqWithSlack(campaignId, partitionKey, message,
                                "THREAD_INTERRUPTED", interruptedException.getMessage());
                        return;
                    }
                }
            }
        }

        sendToDlqWithSlack(campaignId, partitionKey, message, "MAX_RETRY_EXCEEDED", null);
    }

    private String extractUserIdKey(String message, Long campaignId) {
        try {
            ParticipationEvent event = jsonMapper.readValue(message, ParticipationEvent.class);
            return String.valueOf(event.getUserId());
        } catch (Exception e) {
            log.warn("Failed to extract userId partition key. campaignId={}", campaignId, e);
            return String.valueOf(campaignId);
        }
    }

    private void sendToDlqWithSlack(
            Long campaignId,
            String originalKey,
            String originalMessage,
            String errorReason,
            String errorMessage
    ) {
        try {
            DlqEventPayload payload = dlqMessageService.createBridgePayload(
                    campaignId,
                    originalKey,
                    originalMessage,
                    errorReason,
                    errorMessage
            );
            dlqMessageService.publishAndStore(payload);
            log.info("Bridge message moved to DLQ. campaignId={}, reason={}", campaignId, errorReason);
        } catch (Exception e) {
            log.error("Failed to move bridge message to DLQ. campaignId={}", campaignId, e);
        }

        slackNotificationService.sendDlqAlert(
                "Bridge " + errorReason,
                "campaignId=" + campaignId + ", key=" + originalKey
        );
    }
}
