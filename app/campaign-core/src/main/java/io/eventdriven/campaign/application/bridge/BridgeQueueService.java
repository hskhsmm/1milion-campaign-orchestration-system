package io.eventdriven.campaign.application.bridge;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Redis Cluster의 캠페인별 슬롯 안에서 queue → in-flight 이동과 만료 복구를 원자화한다. */
@Service
public class BridgeQueueService {

    private static final String QUEUE_PREFIX = "queue:campaign:{";
    private static final String IN_FLIGHT_PREFIX = "bridge:inflight:campaign:{";
    private static final int MAX_IN_FLIGHT = 10_000;
    /** 한 Lua가 같은 샤드의 접수 Lua를 오래 막지 않도록 초기 상한을 작게 둔다. */
    public static final int MAX_CLAIM_BATCH_SIZE = 50;
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(5);
    private static final int RECLAIM_BATCH = 500;
    private static final DefaultRedisScript<String> CLAIM_SCRIPT = script("scripts/bridge-claim.lua", String.class);
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> CLAIM_BATCH_SCRIPT = script("scripts/bridge-claim-batch.lua", List.class);
    private static final DefaultRedisScript<Long> RECLAIM_SCRIPT = script("scripts/bridge-reclaim.lua", Long.class);

    private final RedisTemplate<String, String> redisTemplate;

    private final MeterRegistry registry;
    private final Timer claimTimer;
    private final Timer ackTimer;
    private final Timer reclaimTimer;
    private final Counter claimCallsClaimed;
    private final Counter claimCallsEmpty;
    private final Counter claimEvents;
    private final DistributionSummary claimBatchSize;
    private final Counter ackRemoved;
    private final Counter ackNotFound;
    private final Counter ackError;

    public BridgeQueueService(RedisTemplate<String, String> redisTemplate, MeterRegistry registry) {
        this.redisTemplate = redisTemplate;
        this.registry = registry;
        this.claimTimer = redisTimer("claim");
        this.ackTimer = redisTimer("ack");
        this.reclaimTimer = redisTimer("reclaim");
        // 호출 수와 이벤트 수는 batch에서 달라진다. 건당 claim 비용 = claim timer 합계 / claim.events.
        this.claimCallsClaimed = registry.counter("bridge.claim.calls", "result", "claimed");
        // 빈 결과는 Queue가 비었는지 in-flight 상한인지 구분하지 않는다.
        this.claimCallsEmpty = registry.counter("bridge.claim.calls", "result", "empty");
        this.claimEvents = Counter.builder("bridge.claim.events")
                .description("Events moved from queue to in-flight")
                .register(registry);
        // ACK 결과: removed=ZREM 1, not_found=ZREM 0(reclaim이 먼저 이김 등), error=예외 또는 결과 없음.
        // result=rejected(ACK 실행기 거부)는 ParticipationBridge가 같은 메트릭에 기록한다.
        this.ackRemoved = registry.counter("bridge.claim.ack.results", "result", "removed");
        this.ackNotFound = registry.counter("bridge.claim.ack.results", "result", "not_found");
        this.ackError = registry.counter("bridge.claim.ack.results", "result", "error");
        this.claimBatchSize = DistributionSummary.builder("bridge.claim.batch.size")
                .description("Events moved per non-empty claim call")
                .serviceLevelObjectives(1, 10, 25, 50)
                .register(registry);
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
            claimCallsEmpty.increment();
            return null;
        }
        Claim claim = parse(campaignId, value);
        recordClaimed(1);
        return claim;
    }

    /**
     * 오래된 순으로 최대 requested건을 한 번의 Lua로 옮긴다. 빈 목록은 Queue가 비었거나 in-flight 상한이다.
     * Redis 응답을 받지 못한 경우 claim이 완료됐을 수 있으며, 그 claim은 lease 만료 후 reclaim된다.
     */
    public List<Claim> claimBatch(Long campaignId, int requested) {
        if (requested < 1 || requested > MAX_CLAIM_BATCH_SIZE) {
            throw new IllegalArgumentException("claim batch size must be 1.." + MAX_CLAIM_BATCH_SIZE + ": " + requested);
        }
        List<?> values = observeRedis(claimTimer, "claim", () -> redisTemplate.execute(CLAIM_BATCH_SCRIPT,
                keys(campaignId), UUID.randomUUID().toString(), String.valueOf(MAX_IN_FLIGHT),
                String.valueOf(requested)));
        if (values == null || values.isEmpty()) {
            claimCallsEmpty.increment();
            return List.of();
        }
        if (values.size() > requested) {
            throw new IllegalStateException("Bridge claimed more than requested. campaignId=" + campaignId
                    + ", requested=" + requested + ", claimed=" + values.size());
        }
        List<Claim> claims = new ArrayList<>(values.size());
        for (Object value : values) {
            if (!(value instanceof String text)) {
                throw new IllegalStateException("Invalid bridge claim type. campaignId=" + campaignId);
            }
            claims.add(parse(campaignId, text));
        }
        recordClaimed(claims.size());
        return claims;
    }

    private void recordClaimed(int count) {
        claimCallsClaimed.increment();
        claimEvents.increment(count);
        claimBatchSize.record(count);
    }

    private static Claim parse(Long campaignId, String value) {
        int separator = value.indexOf('|');
        if (separator < 1) {
            throw new IllegalStateException("Invalid bridge claim. campaignId=" + campaignId);
        }
        return new Claim(campaignId, value, value.substring(separator + 1));
    }

    /** Kafka가 성공을 확인한 claim만 삭제한다. 0이면 만료 복구가 먼저 이겼을 수 있다. */
    public boolean acknowledge(Claim claim) {
        Long removed;
        try {
            removed = observeRedis(ackTimer, "ack", () ->
                    redisTemplate.opsForZSet().remove(inFlightKey(claim.campaignId()), claim.value()));
        } catch (RuntimeException failure) {
            ackError.increment();
            throw failure;
        }
        if (removed == null) {
            ackError.increment();
            return false;
        }
        if (removed == 1) {
            ackRemoved.increment();
            return true;
        }
        ackNotFound.increment();
        return false;
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
