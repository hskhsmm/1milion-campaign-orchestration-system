package io.eventdriven.campaign.application.bridge;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/** Optional localhost-only Redis smoke. Deletes only this test's unique keys. */
@EnabledIfEnvironmentVariable(named = "BRIDGE_TEST_REDIS_PORT", matches = "[0-9]+")
class BridgeQueueObservationRedisTest {
    @Test
    void realRedisClaimAckAndReclaimKeepOriginalAndExportOperationCounts() {
        var factory = new LettuceConnectionFactory("127.0.0.1",
                Integer.parseInt(System.getenv("BRIDGE_TEST_REDIS_PORT")));
        factory.afterPropertiesSet();
        factory.start();
        var redis = new StringRedisTemplate(factory);
        var registry = new SimpleMeterRegistry();
        long campaignId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        String queue = "queue:campaign:{" + campaignId + "}";
        String inFlight = BridgeQueueService.inFlightKey(campaignId);
        try {
            var service = new BridgeQueueService(redis, registry);
            redis.opsForList().leftPush(queue, "original|payload");
            var claim = service.claim(campaignId);
            assertThat(claim.message()).isEqualTo("original|payload");
            assertThat(redis.opsForList().size(queue)).isZero();
            assertThat(redis.opsForZSet().zCard(inFlight)).isEqualTo(1);
            // Force only this claim's lease to expire without waiting five minutes.
            redis.opsForZSet().add(inFlight, claim.value(), 0);
            assertThat(service.reclaimExpired(campaignId)).isEqualTo(1);
            var retry = service.claim(campaignId);
            assertThat(retry.message()).isEqualTo(claim.message());
            assertThat(service.acknowledge(claim)).isFalse(); // stale ACK cannot delete the new claim
            assertThat(redis.opsForZSet().zCard(inFlight)).isEqualTo(1);
            assertThat(service.acknowledge(retry)).isTrue();
            assertThat(service.claim(campaignId)).isNull();
            assertThat(redis.opsForList().size(queue)).isZero();
            assertThat(redis.opsForZSet().zCard(inFlight)).isZero();
            assertThat(registry.get("bridge.redis.duration").tag("operation", "claim").timer().count()).isEqualTo(3);
            assertThat(registry.get("bridge.redis.duration").tag("operation", "ack").timer().count()).isEqualTo(2);
            assertThat(registry.get("bridge.redis.duration").tag("operation", "reclaim").timer().count()).isEqualTo(1);
        } finally {
            redis.delete(List.of(queue, inFlight));
            registry.close();
            factory.destroy();
        }
    }
}
