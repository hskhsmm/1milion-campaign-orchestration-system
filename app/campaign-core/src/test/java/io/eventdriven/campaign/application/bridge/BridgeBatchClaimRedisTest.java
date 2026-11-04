package io.eventdriven.campaign.application.bridge;

import io.lettuce.core.cluster.SlotHash;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Optional localhost-only Redis check for bridge-claim-batch.lua. Deletes only this test's unique keys. */
@EnabledIfEnvironmentVariable(named = "BRIDGE_TEST_REDIS_PORT", matches = "[0-9]+")
class BridgeBatchClaimRedisTest {

    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private SimpleMeterRegistry registry;
    private BridgeQueueService service;
    private long campaignId;
    private String queue;
    private String inFlight;

    @BeforeEach
    void setUp() {
        factory = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(System.getenv("BRIDGE_TEST_REDIS_PORT")));
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        registry = new SimpleMeterRegistry();
        service = new BridgeQueueService(redis, registry);
        campaignId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        queue = "queue:campaign:{" + campaignId + "}";
        inFlight = BridgeQueueService.inFlightKey(campaignId);
    }

    @AfterEach
    void tearDown() {
        redis.delete(List.of(queue, inFlight));
        registry.close();
        factory.destroy();
    }

    /** 접수 Lua와 같은 LPUSH 순서로 넣는다. */
    private void enqueue(List<String> messages) {
        messages.forEach(message -> redis.opsForList().leftPush(queue, message));
    }

    private static List<String> messages(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> "{\"seq\":" + i + "}").toList();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10, 50})
    void movesExactlyNOldestFirstWithUniqueClaims(int size) {
        List<String> input = messages(size + 5);
        enqueue(input);

        var claims = service.claimBatch(campaignId, size);

        assertThat(claims).extracting(BridgeQueueService.Claim::message).containsExactlyElementsOf(input.subList(0, size));
        assertThat(claims).extracting(BridgeQueueService.Claim::value).doesNotHaveDuplicates();
        assertThat(redis.opsForList().size(queue)).isEqualTo(5);
        assertThat(redis.opsForZSet().zCard(inFlight)).isEqualTo(size);
        assertThat(redis.opsForZSet().range(inFlight, 0, -1))
                .containsExactlyInAnyOrderElementsOf(claims.stream().map(BridgeQueueService.Claim::value).toList());
    }

    @Test
    void consecutiveBatchesKeepQueueOrder() {
        enqueue(List.of("1", "2", "3", "4", "5"));

        assertThat(service.claimBatch(campaignId, 3)).extracting(BridgeQueueService.Claim::message)
                .containsExactly("1", "2", "3");
        assertThat(service.claimBatch(campaignId, 3)).extracting(BridgeQueueService.Claim::message)
                .containsExactly("4", "5");
        assertThat(service.claimBatch(campaignId, 3)).isEmpty();
        assertThat(redis.hasKey(queue)).isFalse();
    }

    @Test
    void inFlightLimitAllowsOnlyRemainingRoomThenEmpty() {
        Set<ZSetOperations.TypedTuple<String>> existing = new HashSet<>();
        for (int i = 0; i < 9_995; i++) {
            existing.add(new DefaultTypedTuple<>("existing-" + i + "|x", (double) System.currentTimeMillis()));
        }
        redis.opsForZSet().add(inFlight, existing);
        enqueue(messages(20));

        assertThat(service.claimBatch(campaignId, 10)).hasSize(5);
        assertThat(redis.opsForZSet().zCard(inFlight)).isEqualTo(10_000);
        assertThat(service.claimBatch(campaignId, 10)).isEmpty();
        assertThat(redis.opsForList().size(queue)).isEqualTo(15);
    }

    @Test
    void expiredBatchClaimsAreAllRestoredAndStaleAckCannotDeleteNewClaim() {
        enqueue(List.of("{\"a\":\"x|y\"}", "{\"b\":2}", "{\"c\":3}"));
        var first = service.claimBatch(campaignId, 10);
        assertThat(first).hasSize(3);
        // 이 시험의 claim lease만 만료시킨다.
        first.forEach(claim -> redis.opsForZSet().add(inFlight, claim.value(), 0));

        assertThat(service.reclaimExpired(campaignId)).isEqualTo(3);
        assertThat(redis.opsForZSet().zCard(inFlight)).isZero();
        assertThat(redis.opsForList().range(queue, 0, -1))
                .containsExactlyInAnyOrder("{\"a\":\"x|y\"}", "{\"b\":2}", "{\"c\":3}");

        var retry = service.claimBatch(campaignId, 10);
        assertThat(retry).extracting(BridgeQueueService.Claim::message)
                .containsExactlyInAnyOrder("{\"a\":\"x|y\"}", "{\"b\":2}", "{\"c\":3}");
        first.forEach(stale -> assertThat(service.acknowledge(stale)).isFalse());
        assertThat(redis.opsForZSet().zCard(inFlight)).isEqualTo(3);
        retry.forEach(claim -> assertThat(service.acknowledge(claim)).isTrue());
        assertThat(redis.opsForZSet().zCard(inFlight)).isZero();
        assertThat(registry.get("bridge.claim.ack.results").tag("result", "not_found").counter().count()).isEqualTo(3);
        assertThat(registry.get("bridge.claim.ack.results").tag("result", "removed").counter().count()).isEqualTo(3);
        assertThat(registry.get("bridge.claim.ack.results").tag("result", "error").counter().count()).isZero();
    }

    /** 묶음 ACK: 한 번의 ZREM으로 지우고, reclaim이 먼저 이긴 claim은 not_found로 센다. 새 claim은 지우지 않는다. */
    @Test
    void batchAckRemovesOwnClaimsAndCountsStaleAsNotFound() {
        enqueue(messages(30));
        var first = service.claimBatch(campaignId, 30);
        // 3건만 만료시켜 reclaim → 다시 claim (같은 메시지, 새 token)
        first.subList(0, 3).forEach(claim -> redis.opsForZSet().add(inFlight, claim.value(), 0));
        assertThat(service.reclaimExpired(campaignId)).isEqualTo(3);
        var retry = service.claimBatch(campaignId, 10);
        assertThat(retry).hasSize(3);

        long removed = service.acknowledgeBatch(campaignId, first); // 30건 중 3건은 stale
        assertThat(removed).isEqualTo(27);
        assertThat(redis.opsForZSet().range(inFlight, 0, -1))
                .containsExactlyInAnyOrderElementsOf(retry.stream().map(BridgeQueueService.Claim::value).toList());

        assertThat(service.acknowledgeBatch(campaignId, retry)).isEqualTo(3);
        assertThat(redis.opsForZSet().zCard(inFlight)).isZero();
        assertThat(registry.get("bridge.claim.ack.results").tag("result", "removed").counter().count()).isEqualTo(30);
        assertThat(registry.get("bridge.claim.ack.results").tag("result", "not_found").counter().count()).isEqualTo(3);
        assertThat(registry.get("bridge.ack.batch.size").summary().count()).isEqualTo(2);
        assertThat(registry.get("bridge.ack.batch.size").summary().totalAmount()).isEqualTo(33);
        // ACK timer는 Redis 호출 수(2)다. 이벤트 수는 ack.results 합계.
        assertThat(registry.get("bridge.redis.duration").tag("operation", "ack").timer().count()).isEqualTo(2);
    }

    @Test
    void batchAckRejectsMixedCampaigns() {
        var a = new BridgeQueueService.Claim(campaignId, "t1|m", "m");
        var b = new BridgeQueueService.Claim(campaignId + 1, "t2|m", "m");
        assertThatThrownBy(() -> service.acknowledgeBatch(campaignId, List.of(a, b)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wrongKeyTypeIsRejectedBeforeAnyWrite() {
        redis.opsForValue().set(queue, "not-a-list");
        assertThatThrownBy(() -> service.claimBatch(campaignId, 10)).isInstanceOf(RuntimeException.class);
        assertThat(redis.hasKey(inFlight)).isFalse();
        redis.delete(queue);

        enqueue(messages(3));
        redis.opsForValue().set(inFlight, "not-a-zset");
        assertThatThrownBy(() -> service.claimBatch(campaignId, 10)).isInstanceOf(RuntimeException.class);
        assertThat(redis.opsForList().size(queue)).isEqualTo(3);
        assertThat(registry.get("bridge.redis.errors").tag("operation", "claim").counter().count()).isEqualTo(2);
    }

    @Test
    void queueAndInFlightShareClusterSlot() {
        assertThat(SlotHash.getSlot(queue)).isEqualTo(SlotHash.getSlot(inFlight));
    }
}
