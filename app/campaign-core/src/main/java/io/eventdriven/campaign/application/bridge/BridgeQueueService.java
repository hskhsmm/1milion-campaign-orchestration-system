package io.eventdriven.campaign.application.bridge;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Redis Cluster의 캠페인별 슬롯 안에서 queue → in-flight 이동과 만료 복구를 원자화한다. */
@Service
public class BridgeQueueService {

    private static final String QUEUE_PREFIX = "queue:campaign:{";
    private static final String IN_FLIGHT_PREFIX = "bridge:inflight:campaign:{";
    private static final int MAX_IN_FLIGHT = 10_000;
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(5);
    private static final int RECLAIM_BATCH = 500;
    private static final DefaultRedisScript<String> CLAIM_SCRIPT = script("scripts/bridge-claim.lua", String.class);
    private static final DefaultRedisScript<Long> RECLAIM_SCRIPT = script("scripts/bridge-reclaim.lua", Long.class);

    private final RedisTemplate<String, String> redisTemplate;

    private final MeterRegistry registry;
    private final Timer claimTimer;
    private final Timer ackTimer;
    private final Timer reclaimTimer;
    private final Counter claimed;
    private final Counter notClaimed;

    public BridgeQueueService(RedisTemplate<String, String> redisTemplate, MeterRegistry registry) {
        this.redisTemplate = redisTemplate;
        this.registry = registry;
        this.claimTimer = redisTimer("claim");
        this.ackTimer = redisTimer("ack");
        this.reclaimTimer = redisTimer("reclaim");
        this.claimed = registry.counter("bridge.claim.results", "result", "claimed");
        // null cannot distinguish an empty queue from the in-flight limit.
        this.notClaimed = registry.counter("bridge.claim.results", "result", "not_claimed");
    }

    private Timer redisTimer(String operation) {
        return Timer.builder("bridge.redis.duration")
                .description("Client elapsed Redis call time, including network/connection wait; not Redis server execution time")
                .tag("operation", operation)
                .serviceLevelObjectives(Duration.ofMillis(1), Duration.ofMillis(5), Duration.ofMillis(10),
                        Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofSeconds(1))
                .register(registry);
    }

    private <T> T observeRedis(Timer timer, String operation, java.util.function.Supplier<T> call) {
        try {
            return timer.record(call);
        } catch (RuntimeException failure) {
            registry.counter("bridge.redis.errors", "operation", operation).increment();
            throw failure;
        }
    }

    public Claim claim(Long campaignId) {
        String value = observeRedis(claimTimer, "claim", () -> redisTemplate.execute(CLAIM_SCRIPT, keys(campaignId),
                UUID.randomUUID().toString(), String.valueOf(MAX_IN_FLIGHT)));
        if (value == null) {
            notClaimed.increment();
            return null;
        }
        int separator = value.indexOf('|');
        if (separator < 1) {
            throw new IllegalStateException("Invalid bridge claim. campaignId=" + campaignId);
        }
        claimed.increment();
        return new Claim(campaignId, value, value.substring(separator + 1));
    }

    /** Kafka가 성공을 확인한 claim만 삭제한다. 0이면 만료 복구가 먼저 이겼을 수 있다. */
    public boolean acknowledge(Claim claim) {
        Long removed = observeRedis(ackTimer, "ack", () ->
                redisTemplate.opsForZSet().remove(inFlightKey(claim.campaignId()), claim.value()));
        return removed != null && removed == 1;
    }

    public long reclaimExpired(Long campaignId) {
        Long restored = observeRedis(reclaimTimer, "reclaim", () -> redisTemplate.execute(RECLAIM_SCRIPT, keys(campaignId),
                String.valueOf(CLAIM_LEASE.toMillis()), String.valueOf(RECLAIM_BATCH)));
        if (restored == null) {
            throw new IllegalStateException("Bridge reclaim failed. campaignId=" + campaignId);
        }
        return restored;
    }

    private List<String> keys(Long campaignId) {
        return List.of(QUEUE_PREFIX + campaignId + "}", inFlightKey(campaignId));
    }

    public static String inFlightKey(Long campaignId) {
        return IN_FLIGHT_PREFIX + campaignId + "}";
    }

    private static <T> DefaultRedisScript<T> script(String path, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(resultType);
        return script;
    }

    public record Claim(Long campaignId, String value, String message) { }
}
