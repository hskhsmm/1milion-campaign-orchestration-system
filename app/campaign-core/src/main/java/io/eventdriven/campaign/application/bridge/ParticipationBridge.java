package io.eventdriven.campaign.application.bridge;

import io.eventdriven.campaign.application.event.ParticipationEvent;
import io.eventdriven.campaign.application.service.RedisStockService;
import io.eventdriven.campaign.config.KafkaConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Component
public class ParticipationBridge {

    private static final String ACTIVE_CAMPAIGNS_KEY = "active:campaigns";
    private static final String QUEUE_KEY_PREFIX = "queue:campaign:{";
    private static final String INSTANCE_ID = resolveInstanceId();

    private final RedisTemplate<String, String> redisTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final BridgeQueueService bridgeQueueService;
    private final Executor ackExecutor;
    private final RedisStockService redisStockService;
    private final MeterRegistry meterRegistry;
    private final JsonMapper jsonMapper;
    private final Timer drainTimer;
    private final Map<Long, Counter> publishedCounters = new ConcurrentHashMap<>();

    public ParticipationBridge(
            RedisTemplate<String, String> redisTemplate,
            KafkaTemplate<String, String> kafkaTemplate,
            BridgeQueueService bridgeQueueService,
            @Qualifier("bridgeAckExecutor") Executor ackExecutor,
            RedisStockService redisStockService,
            MeterRegistry meterRegistry,
            JsonMapper jsonMapper
    ) {
        this.redisTemplate = redisTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.bridgeQueueService = bridgeQueueService;
        this.ackExecutor = ackExecutor;
        this.redisStockService = redisStockService;
        this.meterRegistry = meterRegistry;
        this.jsonMapper = jsonMapper;
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

    @Scheduled(fixedDelay = 5_000)
    public void reclaimExpiredClaims() {
        Set<String> campaignIds = redisTemplate.opsForSet().members(ACTIVE_CAMPAIGNS_KEY);
        if (campaignIds == null) {
            return;
        }
        for (String campaignIdStr : campaignIds) {
            try {
                Long campaignId = Long.parseLong(campaignIdStr);
                long restored = bridgeQueueService.reclaimExpired(campaignId);
                if (restored > 0) {
                    meterRegistry.counter("bridge.claims.reclaimed", "campaignId", campaignIdStr).increment(restored);
                    log.warn("Expired bridge claims returned to queue. campaignId={}, count={}", campaignId, restored);
                }
            } catch (Exception e) {
                log.error("Failed to reclaim bridge claims. campaignId={}", campaignIdStr, e);
            }
        }
    }

    private void drainCampaignQueue(Long campaignId) {
        String queueKey = QUEUE_KEY_PREFIX + campaignId + "}";
        Long queueSize = redisTemplate.opsForList().size(queueKey);
        int dynamicBatchSize = resolveBatchSize(queueSize);

        for (int i = 0; i < dynamicBatchSize; i++) {
            BridgeQueueService.Claim claim = bridgeQueueService.claim(campaignId);
            if (claim == null) {
                if (!redisStockService.isActive(campaignId)) {
                    deactivateIfQueueEmpty(campaignId);
                }
                break;
            }
            publishAsync(claim);
        }
    }

    // Recovery activation and Set removal must not interleave across app instances.
    // RedisStockService takes a per-campaign lock and rechecks flag/LLEN/in-flight before SREM.
    private void deactivateIfQueueEmpty(Long campaignId) {
        if (redisStockService.deactivateIfQueueEmpty(campaignId)) {
            log.info("Campaign drained and deactivated. campaignId={}, instance={}", campaignId, INSTANCE_ID);
        }
    }

    private static String resolveInstanceId() {
        String hostname = System.getenv("HOSTNAME");
        if (hostname != null && !hostname.isBlank()) {
            return hostname;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
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

    private void publishAsync(BridgeQueueService.Claim claim) {
        Long campaignId = claim.campaignId();
        String message = claim.message();
        String partitionKey = extractUserIdKey(message, campaignId);
        try {
            kafkaTemplate.send(KafkaConfig.TOPIC_NAME, partitionKey, message)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.warn("Kafka publish failed; claim retained for retry. campaignId={}", campaignId, ex);
                            return;
                        }

                        // Kafka 성공과 Redis claim ACK는 별개다. ACK 실행기가 거부해도 발행 성공은 기록한다.
                        publishedCounters.computeIfAbsent(campaignId, id ->
                                Counter.builder("bridge.messages.published")
                                        .description("Kafka messages published by bridge")
                                        .tag("campaignId", String.valueOf(id))
                                        .register(meterRegistry)
                        ).increment();

                        try {
                            ackExecutor.execute(() -> {
                                try {
                                    if (!bridgeQueueService.acknowledge(claim)) {
                                        log.warn("Kafka succeeded after claim was reclaimed; duplicate may be retried. campaignId={}", campaignId);
                                    }
                                } catch (Exception ackFailure) {
                                    log.error("Kafka succeeded but Redis claim ack failed; duplicate may be retried. campaignId={}",
                                            campaignId, ackFailure);
                                }
                            });
                        } catch (RejectedExecutionException rejected) {
                            // 전송 성공 이후 ACK만 거부된 경우에만 이 지표를 올린다.
                            meterRegistry.counter("bridge.claims.ack.rejected").increment();
                            log.error("Kafka succeeded but Redis claim ACK execution was rejected; claim retained for retry. campaignId={}",
                                    campaignId, rejected);
                        }
                    });
        } catch (Exception e) {
            log.warn("Kafka publish failed before enqueue; claim retained for retry. campaignId={}", campaignId, e);
        }
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
}
