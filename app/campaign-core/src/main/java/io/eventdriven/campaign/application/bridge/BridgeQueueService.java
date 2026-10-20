package io.eventdriven.campaign.application.bridge;

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

    public BridgeQueueService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Claim claim(Long campaignId) {
        String value = redisTemplate.execute(CLAIM_SCRIPT, keys(campaignId),
                UUID.randomUUID().toString(), String.valueOf(MAX_IN_FLIGHT));
        if (value == null) {
            return null;
        }
        int separator = value.indexOf('|');
        if (separator < 1) {
            throw new IllegalStateException("Invalid bridge claim. campaignId=" + campaignId);
        }
        return new Claim(campaignId, value, value.substring(separator + 1));
    }

    /** Kafka가 성공을 확인한 claim만 삭제한다. 0이면 만료 복구가 먼저 이겼을 수 있다. */
    public boolean acknowledge(Claim claim) {
        Long removed = redisTemplate.opsForZSet().remove(inFlightKey(claim.campaignId()), claim.value());
        return removed != null && removed == 1;
    }

    public long reclaimExpired(Long campaignId) {
        Long restored = redisTemplate.execute(RECLAIM_SCRIPT, keys(campaignId),
                String.valueOf(CLAIM_LEASE.toMillis()), String.valueOf(RECLAIM_BATCH));
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
