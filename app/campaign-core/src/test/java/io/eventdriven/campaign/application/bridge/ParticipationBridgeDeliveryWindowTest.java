package io.eventdriven.campaign.application.bridge;

import io.eventdriven.campaign.application.event.ParticipationEvent;
import io.eventdriven.campaign.application.service.RedisStockService;
import io.eventdriven.campaign.config.KafkaConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kafka ack 전에는 claim을 삭제하지 않는 전달 경계를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ParticipationBridgeDeliveryWindowTest {

    private static final String QUEUE_KEY = "queue:campaign:{91}";
    private static final String MESSAGE = "{\"campaignId\":91,\"userId\":7,\"sequence\":1}";

    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private KafkaTemplate<String, String> kafkaTemplate;
    @Mock private BridgeQueueService bridgeQueueService;
    @Mock private RedisStockService redisStockService;
    @Mock private JsonMapper jsonMapper;
    @Mock private SetOperations<String, String> setOperations;
    @Mock private ListOperations<String, String> listOperations;

    @Test
    void claimIsAcknowledgedOnlyAfterKafkaSuccess() throws Exception {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members("active:campaigns")).thenReturn(Set.of("91"));
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.size(QUEUE_KEY)).thenReturn(1L);
        BridgeQueueService.Claim claim = new BridgeQueueService.Claim(91L, "token|" + MESSAGE, MESSAGE);
        when(bridgeQueueService.claim(91L)).thenReturn(claim, (BridgeQueueService.Claim) null);
        when(redisStockService.isActive(91L)).thenReturn(true);
        when(jsonMapper.readValue(MESSAGE, ParticipationEvent.class))
                .thenReturn(new ParticipationEvent(91L, 7L, 1L));

        CompletableFuture<SendResult<String, String>> unacknowledged = new CompletableFuture<>();
        when(kafkaTemplate.send(KafkaConfig.TOPIC_NAME, "7", MESSAGE)).thenReturn(unacknowledged);

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ParticipationBridge bridge = new ParticipationBridge(
                redisTemplate, kafkaTemplate, bridgeQueueService, Runnable::run, redisStockService,
                meterRegistry, jsonMapper);
        bridge.drainQueues();

        assertThat(unacknowledged).isNotDone();
        verify(bridgeQueueService, never()).acknowledge(claim);
        assertThat(meterRegistry.find("bridge.messages.published").counter()).isNull();
        unacknowledged.complete(null);
        verify(bridgeQueueService).acknowledge(claim);
    }

    @Test
    void failedKafkaSendRetainsClaimForReclaim() throws Exception {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members("active:campaigns")).thenReturn(Set.of("91"));
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.size(QUEUE_KEY)).thenReturn(1L);
        BridgeQueueService.Claim claim = new BridgeQueueService.Claim(91L, "token|" + MESSAGE, MESSAGE);
        when(bridgeQueueService.claim(91L)).thenReturn(claim, (BridgeQueueService.Claim) null);
        when(redisStockService.isActive(91L)).thenReturn(true);
        when(jsonMapper.readValue(MESSAGE, ParticipationEvent.class))
                .thenReturn(new ParticipationEvent(91L, 7L, 1L));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        when(kafkaTemplate.send(KafkaConfig.TOPIC_NAME, "7", MESSAGE)).thenReturn(failed);

        ParticipationBridge bridge = new ParticipationBridge(redisTemplate, kafkaTemplate,
                bridgeQueueService, Runnable::run, redisStockService, new SimpleMeterRegistry(), jsonMapper);
        bridge.drainQueues();
        failed.completeExceptionally(new IllegalStateException("broker unavailable"));

        verify(bridgeQueueService, never()).acknowledge(claim);
    }

    @Test
    void successfulKafkaCallbackDoesNotAcknowledgeOnCompletingThread() throws Exception {
        BridgeQueueService.Claim claim = prepareClaim();
        CompletableFuture<SendResult<String, String>> send = new CompletableFuture<>();
        when(kafkaTemplate.send(KafkaConfig.TOPIC_NAME, "7", MESSAGE)).thenReturn(send);
        AtomicReference<Runnable> queuedAck = new AtomicReference<>();
        Executor controlledExecutor = queuedAck::set;

        ParticipationBridge bridge = new ParticipationBridge(redisTemplate, kafkaTemplate,
                bridgeQueueService, controlledExecutor, redisStockService, new SimpleMeterRegistry(), jsonMapper);
        bridge.drainQueues();
        send.complete(null);

        assertThat(queuedAck.get()).isNotNull();
        verify(bridgeQueueService, never()).acknowledge(claim);
        queuedAck.get().run();
        verify(bridgeQueueService).acknowledge(claim);
    }

    @Test
    void rejectedAckExecutionRetainsClaimForLeaseRetry() throws Exception {
        BridgeQueueService.Claim claim = prepareClaim();
        CompletableFuture<SendResult<String, String>> send = new CompletableFuture<>();
        when(kafkaTemplate.send(KafkaConfig.TOPIC_NAME, "7", MESSAGE)).thenReturn(send);
        Executor rejectingExecutor = command -> { throw new RejectedExecutionException("full"); };
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        ParticipationBridge bridge = new ParticipationBridge(redisTemplate, kafkaTemplate,
                bridgeQueueService, rejectingExecutor, redisStockService, meterRegistry, jsonMapper);
        bridge.drainQueues();
        send.complete(null);

        verify(bridgeQueueService, never()).acknowledge(claim);
        assertThat(meterRegistry.counter("bridge.claims.ack.rejected").count()).isEqualTo(1);
    }

    private BridgeQueueService.Claim prepareClaim() throws Exception {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members("active:campaigns")).thenReturn(Set.of("91"));
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.size(QUEUE_KEY)).thenReturn(1L);
        BridgeQueueService.Claim claim = new BridgeQueueService.Claim(91L, "token|" + MESSAGE, MESSAGE);
        when(bridgeQueueService.claim(91L)).thenReturn(claim, (BridgeQueueService.Claim) null);
        when(redisStockService.isActive(91L)).thenReturn(true);
        when(jsonMapper.readValue(MESSAGE, ParticipationEvent.class))
                .thenReturn(new ParticipationEvent(91L, 7L, 1L));
        return claim;
    }
}
