package io.eventdriven.campaign.application.bridge;

import io.eventdriven.campaign.application.event.ParticipationEvent;
import io.eventdriven.campaign.application.service.RedisStockService;
import io.eventdriven.campaign.config.KafkaConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** batch claim에서도 회차 한도는 이벤트 수로 지키고, 실패한 send의 claim만 남는지 검증한다. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ParticipationBridgeBatchClaimTest {

    private static final String QUEUE_KEY = "queue:campaign:{91}";

    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private KafkaTemplate<String, String> kafkaTemplate;
    @Mock private BridgeQueueService bridgeQueueService;
    @Mock private RedisStockService redisStockService;
    @Mock private JsonMapper jsonMapper;
    @Mock private SetOperations<String, String> setOperations;
    @Mock private ListOperations<String, String> listOperations;

    private final AtomicInteger sequence = new AtomicInteger();

    private ParticipationBridge bridge(int claimBatchSize, long queueSize) throws Exception {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members("active:campaigns")).thenReturn(Set.of("91"));
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.size(QUEUE_KEY)).thenReturn(queueSize);
        when(redisStockService.isActive(91L)).thenReturn(true);
        when(jsonMapper.readValue(anyString(), eq(ParticipationEvent.class)))
                .thenReturn(new ParticipationEvent(91L, 7L, 1L));
        when(kafkaTemplate.send(eq(KafkaConfig.TOPIC_NAME), anyString(), anyString()))
                .thenAnswer(invocation -> CompletableFuture.completedFuture(null));
        return new ParticipationBridge(redisTemplate, kafkaTemplate, bridgeQueueService, Runnable::run,
                redisStockService, new SimpleMeterRegistry(), jsonMapper, claimBatchSize);
    }

    private List<BridgeQueueService.Claim> claims(int count) {
        List<BridgeQueueService.Claim> claims = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String message = "m" + sequence.incrementAndGet();
            claims.add(new BridgeQueueService.Claim(91L, "t:" + i + "|" + message, message));
        }
        return claims;
    }

    /** Queue가 충분할 때 요청한 만큼 돌려주는 claimBatch. 요청 크기를 기록한다. */
    private List<Integer> answerFully() {
        List<Integer> requests = new ArrayList<>();
        when(bridgeQueueService.claimBatch(eq(91L), anyInt())).thenAnswer(invocation -> {
            int requested = invocation.getArgument(1);
            requests.add(requested);
            return claims(requested);
        });
        return requests;
    }

    @Test
    void sizeOneKeepsSingleClaimPath() throws Exception {
        ParticipationBridge bridge = bridge(1, 1);
        when(bridgeQueueService.claim(91L)).thenReturn(claims(1).get(0), (BridgeQueueService.Claim) null);

        bridge.drainQueues();

        verify(bridgeQueueService, never()).claimBatch(any(), anyInt());
        verify(kafkaTemplate).send(eq(KafkaConfig.TOPIC_NAME), anyString(), eq("m1"));
    }

    @Test
    void nonDivisorBatchSizeStopsExactlyAtRoundEventLimit() throws Exception {
        ParticipationBridge bridge = bridge(30, 5_000); // Queue < 10,000 → 회차 한도 500건
        List<Integer> requests = answerFully();

        bridge.drainQueues();

        assertThat(requests).hasSize(17);
        assertThat(requests.subList(0, 16)).allMatch(size -> size == 30);
        assertThat(requests.get(16)).isEqualTo(20);
        assertThat(requests.stream().mapToInt(Integer::intValue).sum()).isEqualTo(500);
        verify(kafkaTemplate, org.mockito.Mockito.times(500)).send(eq(KafkaConfig.TOPIC_NAME), anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(longs = {50_000, 500_000})
    void largerQueuesUseSameEventLimitsAsBefore(long queueSize) throws Exception {
        ParticipationBridge bridge = bridge(50, queueSize);
        List<Integer> requests = answerFully();

        bridge.drainQueues();

        int expected = queueSize < 100_000 ? 1_000 : 2_000;
        assertThat(requests.stream().mapToInt(Integer::intValue).sum()).isEqualTo(expected);
        assertThat(requests).hasSize(expected / 50);
    }

    @Test
    void partialBatchStopsRoundWithoutDeactivation() throws Exception {
        ParticipationBridge bridge = bridge(10, 3);
        when(bridgeQueueService.claimBatch(91L, 10)).thenReturn(claims(3));

        bridge.drainQueues();

        verify(bridgeQueueService).claimBatch(91L, 10);
        verify(kafkaTemplate, org.mockito.Mockito.times(3)).send(eq(KafkaConfig.TOPIC_NAME), anyString(), anyString());
        verify(redisStockService, never()).deactivateIfQueueEmpty(91L);
    }

    @Test
    void emptyBatchEndsRoundAndDeactivatesOnlyInactiveCampaign() throws Exception {
        ParticipationBridge bridge = bridge(10, 0);
        when(bridgeQueueService.claimBatch(91L, 10)).thenReturn(List.of());
        when(redisStockService.isActive(91L)).thenReturn(false);

        bridge.drainQueues();

        verify(bridgeQueueService).claimBatch(91L, 10);
        verify(redisStockService).deactivateIfQueueEmpty(91L);
        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    @Test
    void failedSendInBatchRetainsOnlyThatClaim() throws Exception {
        ParticipationBridge bridge = bridge(10, 3);
        List<BridgeQueueService.Claim> batch = claims(3);
        when(bridgeQueueService.claimBatch(91L, 10)).thenReturn(batch);
        when(kafkaTemplate.send(KafkaConfig.TOPIC_NAME, "7", "m2"))
                .thenReturn(CompletableFuture.<SendResult<String, String>>failedFuture(new RuntimeException("broker down")));
        when(kafkaTemplate.send(KafkaConfig.TOPIC_NAME, "7", "m3"))
                .thenThrow(new RuntimeException("buffer full"));
        when(bridgeQueueService.acknowledge(any())).thenReturn(true);

        bridge.drainQueues();

        verify(bridgeQueueService).acknowledge(batch.get(0));
        verify(bridgeQueueService, never()).acknowledge(batch.get(1));
        verify(bridgeQueueService, never()).acknowledge(batch.get(2));
    }

    /** ACK drainer 실행이 거부되면 ACK하지 않고 claim을 in-flight에 남긴다(lease 만료 후 reclaim). */
    @Test
    void rejectedAckDrainerRetainsClaimWithoutAck() throws Exception {
        bridge(10, 1); // 공통 stub
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        java.util.concurrent.Executor rejecting = command -> {
            throw new java.util.concurrent.RejectedExecutionException("ack executor full");
        };
        ParticipationBridge bridge = new ParticipationBridge(redisTemplate, kafkaTemplate, bridgeQueueService,
                rejecting, redisStockService, registry, jsonMapper, 10);
        when(bridgeQueueService.claimBatch(91L, 10)).thenReturn(claims(1));

        bridge.drainQueues();

        verify(bridgeQueueService, never()).acknowledge(any());
        verify(bridgeQueueService, never()).acknowledgeBatch(any(), any());
        assertThat(registry.get("bridge.ack.pending").gauge().value()).isEqualTo(1);
    }

    @Test
    void sendOrderWithinBatchFollowsClaimOrder() throws Exception {
        ParticipationBridge bridge = bridge(10, 3);
        when(bridgeQueueService.claimBatch(91L, 10)).thenReturn(claims(3));

        bridge.drainQueues();

        var order = org.mockito.Mockito.inOrder(kafkaTemplate);
        order.verify(kafkaTemplate).send(KafkaConfig.TOPIC_NAME, "7", "m1");
        order.verify(kafkaTemplate).send(KafkaConfig.TOPIC_NAME, "7", "m2");
        order.verify(kafkaTemplate).send(KafkaConfig.TOPIC_NAME, "7", "m3");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 51, 100})
    void outOfRangeConfigFailsAtConstruction(int claimBatchSize) {
        assertThatThrownBy(() -> new ParticipationBridge(redisTemplate, kafkaTemplate, bridgeQueueService,
                Runnable::run, redisStockService, new SimpleMeterRegistry(), jsonMapper, claimBatchSize))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bridge.claim-batch-size");
    }
}
