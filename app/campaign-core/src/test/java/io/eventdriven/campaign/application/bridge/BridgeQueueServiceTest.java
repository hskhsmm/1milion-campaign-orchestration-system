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
        assertThat(ackCount("removed")).isEqualTo(1);
        assertThat(ackCount("not_found")).isZero();
        assertThat(ackCount("error")).isZero();
    }

    @Test
    void ackResultsSeparateNotFoundAndError() {
        BridgeQueueService.Claim claim = new BridgeQueueService.Claim(91L, "id|message", "message");
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        var failure = new IllegalStateException("redis unavailable");
        when(zSetOperations.remove("bridge:inflight:campaign:{91}", "id|message"))
                .thenReturn(0L, (Long) null).thenThrow(failure);
        var service = new BridgeQueueService(redisTemplate, registry);

        assertThat(service.acknowledge(claim)).isFalse();          // reclaim이 먼저 이긴 경우
        assertThat(service.acknowledge(claim)).isFalse();          // 결과 없음
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.acknowledge(claim)).isSameAs(failure);

        assertThat(ackCount("removed")).isZero();
        assertThat(ackCount("not_found")).isEqualTo(1);
        assertThat(ackCount("error")).isEqualTo(2);
        assertThat(registry.get("bridge.redis.errors").tag("operation", "ack").counter().count()).isEqualTo(1);
    }

    private double ackCount(String result) {
        return registry.get("bridge.claim.ack.results").tag("result", result).counter().count();
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
        assertThat(timedRegistry.get("bridge.claim.calls").tag("result", "claimed").counter().count()).isEqualTo(1);
        assertThat(timedRegistry.get("bridge.claim.events").counter().count()).isEqualTo(1);
    }

    @Test
    void noClaimIsNotReportedAsAnErrorOrAClaimedMessage() {
        var service = new BridgeQueueService(redisTemplate, registry);
        assertThat(service.claim(91L)).isNull();
        assertThat(registry.get("bridge.claim.calls").tag("result", "empty").counter().count()).isEqualTo(1);
        assertThat(registry.get("bridge.claim.calls").tag("result", "claimed").counter().count()).isZero();
        assertThat(registry.get("bridge.claim.events").counter().count()).isZero();
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
            String scrape = prometheus.scrape();
            assertThat(scrape).contains("bridge_redis_duration_seconds_bucket",
                    "operation=\"claim\"", "bridge_claim_calls_total", "bridge_claim_events_total");
            var claimBounds = scrape.lines()
                    .filter(line -> line.startsWith("bridge_redis_duration_seconds_bucket{")
                            && line.contains("operation=\"claim\""))
                    .map(line -> line.replaceAll(".*le=\"([^\"]+)\".*", "$1"))
                    .map(value -> "+Inf".equals(value) ? Double.POSITIVE_INFINITY : Double.parseDouble(value))
                    .collect(java.util.stream.Collectors.toSet());
            assertThat(claimBounds).contains(0.001, 0.005, 0.01, 0.05, 0.1, 1.0, Double.POSITIVE_INFINITY);
        } finally {
            prometheus.close();
        }
    }

    @Test
    void claimBatchReturnsClaimsInOrderAndSeparatesCallAndEventCounts() {
        List<String> keys = List.of("queue:campaign:{91}", "bridge:inflight:campaign:{91}");
        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(keys), anyString(), eq("10000"), eq("10")))
                .thenReturn(List.of("t:0|first", "t:1|second|with-pipe", "t:2|third"));
        var service = new BridgeQueueService(redisTemplate, registry);

        var claims = service.claimBatch(91L, 10);

        assertThat(claims).extracting(BridgeQueueService.Claim::message)
                .containsExactly("first", "second|with-pipe", "third");
        assertThat(claims).extracting(BridgeQueueService.Claim::value).doesNotHaveDuplicates();
        assertThat(registry.get("bridge.claim.calls").tag("result", "claimed").counter().count()).isEqualTo(1);
        assertThat(registry.get("bridge.claim.events").counter().count()).isEqualTo(3);
        var size = registry.get("bridge.claim.batch.size").summary();
        assertThat(size.count()).isEqualTo(1);
        assertThat(size.totalAmount()).isEqualTo(3);
        assertThat(registry.get("bridge.redis.duration").tag("operation", "claim").timer().count()).isEqualTo(1);
    }

    @Test
    void emptyClaimBatchCountsEmptyCallOnly() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), any(List.class), anyString(), eq("10000"), eq("10")))
                .thenReturn(List.of());
        var service = new BridgeQueueService(redisTemplate, registry);

        assertThat(service.claimBatch(91L, 10)).isEmpty();
        assertThat(registry.get("bridge.claim.calls").tag("result", "empty").counter().count()).isEqualTo(1);
        assertThat(registry.get("bridge.claim.events").counter().count()).isZero();
        assertThat(registry.get("bridge.claim.batch.size").summary().count()).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, -1, 51})
    void claimBatchRejectsOutOfRangeSizeWithoutCallingRedis(int requested) {
        var service = new BridgeQueueService(redisTemplate, registry);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.claimBatch(91L, requested))
                .isInstanceOf(IllegalArgumentException.class);
        org.mockito.Mockito.verifyNoInteractions(redisTemplate);
    }

    @Test
    void claimBatchRejectsMoreClaimsThanRequestedOrInvalidFormat() {
        var service = new BridgeQueueService(redisTemplate, registry);
        when(redisTemplate.execute(any(DefaultRedisScript.class), any(List.class), anyString(), eq("10000"), eq("2")))
                .thenReturn(List.of("t:0|a", "t:1|b", "t:2|c"), List.of("missing-separator"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.claimBatch(91L, 2))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.claimBatch(91L, 2))
                .isInstanceOf(IllegalStateException.class);
        assertThat(registry.get("bridge.claim.events").counter().count()).isZero();
    }

}
