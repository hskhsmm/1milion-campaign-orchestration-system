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
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

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
    private final int claimBatchSize;
    private final Timer drainTimer;
    private final Timer kafkaSendTimer;
    private final Timer kafkaConfirmTimer;
    private final Map<Long, Counter> publishedCounters = new ConcurrentHashMap<>();
    private final BridgeAckDispatcher ackDispatcher;
    private final Counter claimSkippedAckBacklog;

    /** ACK drainer 수. bridgeAckExecutor 스레드 수(4)와 같다. */
    private static final int ACK_DRAINERS = 4;
    /** 대기 ACK가 이 값 이상이면 claim을 한 회차 쉰다. 한 회차 claim 한도(최대 2,000)의 몇 배로 둔다. */
    static final int ACK_BACKLOG_PAUSE = 5_000;

    /** 기존 단건 claim 경로(claim-batch-size=1). */
    public ParticipationBridge(
            RedisTemplate<String, String> redisTemplate,
            KafkaTemplate<String, String> kafkaTemplate,
            BridgeQueueService bridgeQueueService,
            Executor ackExecutor,
            RedisStockService redisStockService,
            MeterRegistry meterRegistry,
            JsonMapper jsonMapper
    ) {
        this(redisTemplate, kafkaTemplate, bridgeQueueService, ackExecutor, redisStockService,
                meterRegistry, jsonMapper, 1);
    }

    @Autowired
    public ParticipationBridge(
            RedisTemplate<String, String> redisTemplate,
            KafkaTemplate<String, String> kafkaTemplate,
            BridgeQueueService bridgeQueueService,
            @Qualifier("bridgeAckExecutor") Executor ackExecutor,
            RedisStockService redisStockService,
            MeterRegistry meterRegistry,
            JsonMapper jsonMapper,
            @Value("${bridge.claim-batch-size:1}") int claimBatchSize
    ) {
        if (claimBatchSize < 1 || claimBatchSize > BridgeQueueService.MAX_CLAIM_BATCH_SIZE) {
            throw new IllegalArgumentException("bridge.claim-batch-size must be 1.."
                    + BridgeQueueService.MAX_CLAIM_BATCH_SIZE + ": " + claimBatchSize);
        }
        this.claimBatchSize = claimBatchSize;
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
        this.kafkaSendTimer = Timer.builder("bridge.kafka.send.duration")
                .description("Synchronous KafkaTemplate.send call time; excludes asynchronous confirmation")
                .serviceLevelObjectives(Duration.ofMillis(1), Duration.ofMillis(10),
                        Duration.ofMillis(100), Duration.ofSeconds(1), Duration.ofSeconds(60))
                .register(meterRegistry);
        this.kafkaConfirmTimer = Timer.builder("bridge.kafka.confirm.duration")
                .description("Time from send start to success/failure callback; not DB confirmation or Redis ACK")
                .register(meterRegistry);
        this.ackDispatcher = new BridgeAckDispatcher(bridgeQueueService, ackExecutor, ACK_DRAINERS, meterRegistry);
        this.claimSkippedAckBacklog = meterRegistry.counter("bridge.claim.skipped", "reason", "ack_backlog");
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
        // 역압: ACK가 밀리면 in-flight가 "발행됐지만 지워지지 않은" claim으로 차서 상한에 막히고 lease 만료를 기다리게 된다.
        // 대기 ACK가 많으면 이번 회차 claim을 쉬어 이벤트를 Queue에 둔다(유실 없음, 다음 회차에 다시 시도).
        if (ackDispatcher.pendingCount() >= ACK_BACKLOG_PAUSE) {
            claimSkippedAckBacklog.increment();
            return;
        }
        String queueKey = QUEUE_KEY_PREFIX + campaignId + "}";
        Long queueSize = redisTemplate.opsForList().size(queueKey);
        // 회차 한도는 호출 수가 아니라 이벤트 수다(앱 인스턴스·캠페인별 drain 1회 기준).
        int dynamicBatchSize = resolveBatchSize(queueSize);
        if (claimBatchSize > 1) {
            drainInBatches(campaignId, dynamicBatchSize);
            return;
        }

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

    private void drainInBatches(Long campaignId, int eventLimit) {
        int remaining = eventLimit;
        while (remaining > 0) {
            int requested = Math.min(claimBatchSize, remaining);
            List<BridgeQueueService.Claim> claims = bridgeQueueService.claimBatch(campaignId, requested);
            if (claims.isEmpty()) {
                if (!redisStockService.isActive(campaignId)) {
                    deactivateIfQueueEmpty(campaignId);
                }
                break;
            }
            // 한 batch 안의 send 호출 순서만 오래된 순이다. 인스턴스 간·Kafka 비동기 전체 순서는 보장하지 않는다.
            claims.forEach(this::publishAsync);
            remaining -= claims.size();
            if (claims.size() < requested) {
                break; // Queue 소진 또는 in-flight 상한. 비활성화 판단은 다음 회차의 빈 결과에서 한다.
            }
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
            Timer.Sample confirmation = Timer.start(meterRegistry);
            CompletableFuture<SendResult<String, String>> send;
            try {
                send = kafkaSendTimer.record(() -> kafkaTemplate.send(KafkaConfig.TOPIC_NAME, partitionKey, message));
            } catch (RuntimeException failure) {
                confirmation.stop(kafkaConfirmTimer);
                throw failure;
            }
            send.whenComplete((result, ex) -> {
                        confirmation.stop(kafkaConfirmTimer);
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

                        // Redis ACK는 Kafka 완료 스레드에서 하지 않고 대기열에 넣어 묶음으로 처리한다.
                        if (!ackDispatcher.submit(claim)) {
                            // 전송 성공 이후 ACK만 거부된 경우에만 이 지표를 올린다.
                            meterRegistry.counter("bridge.claims.ack.rejected").increment(); // 기존 대시보드 호환
                            meterRegistry.counter("bridge.claim.ack.results", "result", "rejected").increment();
                            log.error("Kafka succeeded but Redis claim ACK queue is full; claim retained for retry. campaignId={}, pending={}",
                                    campaignId, ackDispatcher.pendingCount());
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
