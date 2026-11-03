package io.eventdriven.campaign.application.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class BridgeQueueServiceTest {

    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private ZSetOperations<String, String> zSetOperations;

    @Test
    void claimUsesSameClusterSlotAndPreservesOriginalMessage() {
        String message = "{\"campaignId\":91,\"payload\":\"a|b\"}";
        List<String> keys = List.of("queue:campaign:{91}", "bridge:inflight:campaign:{91}");
        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(keys), anyString(), eq("10000")))
                .thenReturn("unique-token|" + message);

        BridgeQueueService.Claim claim = new BridgeQueueService(redisTemplate, registry).claim(91L);

        assertThat(claim.message()).isEqualTo(message);
        assertThat(claim.value()).isEqualTo("unique-token|" + message);
    }

    @Test
    void acknowledgeRemovesOnlyThatClaim() {
        BridgeQueueService.Claim claim = new BridgeQueueService.Claim(91L, "id|message", "message");
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.remove("bridge:inflight:campaign:{91}", "id|message")).thenReturn(1L);

        assertThat(new BridgeQueueService(redisTemplate, registry).acknowledge(claim)).isTrue();
        verify(zSetOperations).remove("bridge:inflight:campaign:{91}", "id|message");
    }

    @Test
    void reclaimUsesSameClusterSlot() {
        List<String> keys = List.of("queue:campaign:{91}", "bridge:inflight:campaign:{91}");
        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(keys), eq("300000"), eq("500")))
                .thenReturn(3L);

        assertThat(new BridgeQueueService(redisTemplate, registry).reclaimExpired(91L)).isEqualTo(3L);
    }
    @Test
    void claimLatencyIncludesClientWaitAndCountsOnlySuccessfulClaims() {
        var clock = new io.micrometer.core.instrument.MockClock();
        var timedRegistry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry(
                io.micrometer.core.instrument.simple.SimpleConfig.DEFAULT, clock);
        when(redisTemplate.execute(any(DefaultRedisScript.class), any(List.class), anyString(), eq("10000")))
                .thenAnswer(invocation -> { clock.add(java.time.Duration.ofMillis(5)); return "token|message"; });
        var service = new BridgeQueueService(redisTemplate, timedRegistry);
        assertThat(service.claim(91L).message()).isEqualTo("message");
        var timer = timedRegistry.get("bridge.redis.duration").tag("operation", "claim").timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(5);
        assertThat(timedRegistry.get("bridge.claim.results").tag("result", "claimed").counter().count()).isEqualTo(1);
    }

    @Test
    void noClaimIsNotReportedAsAnErrorOrAClaimedMessage() {
        var service = new BridgeQueueService(redisTemplate, registry);
        assertThat(service.claim(91L)).isNull();
        assertThat(registry.get("bridge.claim.results").tag("result", "not_claimed").counter().count()).isEqualTo(1);
        assertThat(registry.get("bridge.claim.results").tag("result", "claimed").counter().count()).isZero();
        assertThat(registry.find("bridge.redis.errors").counter()).isNull();
    }

    @Test
    void redisFailureIsMeasuredAndPropagatedWithoutRetrying() {
        var failure = new IllegalStateException("redis unavailable");
        when(redisTemplate.execute(any(DefaultRedisScript.class), any(List.class), anyString(), eq("10000")))
                .thenThrow(failure);
        var service = new BridgeQueueService(redisTemplate, registry);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.claim(91L)).isSameAs(failure);
        assertThat(registry.get("bridge.redis.duration").tag("operation", "claim").timer().count()).isEqualTo(1);
        assertThat(registry.get("bridge.redis.errors").tag("operation", "claim").counter().count()).isEqualTo(1);
        verify(redisTemplate).execute(any(DefaultRedisScript.class), any(List.class), anyString(), eq("10000"));
    }

    @Test
    void prometheusExportsBoundedOperationBuckets() {
        var prometheus = new io.micrometer.prometheusmetrics.PrometheusMeterRegistry(
                io.micrometer.prometheusmetrics.PrometheusConfig.DEFAULT);
        try {
            new BridgeQueueService(redisTemplate, prometheus).claim(91L);
            assertThat(prometheus.scrape()).contains("bridge_redis_duration_seconds_bucket",
                    "operation=\"claim\"", "bridge_claim_results_total");
        } finally {
            prometheus.close();
        }
    }

}
