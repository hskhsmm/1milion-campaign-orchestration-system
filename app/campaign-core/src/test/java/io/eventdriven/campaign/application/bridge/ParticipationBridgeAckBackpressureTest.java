package io.eventdriven.campaign.application.bridge;

import io.eventdriven.campaign.application.event.ParticipationEvent;
import io.eventdriven.campaign.application.service.RedisStockService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** ACK가 밀리면 claim을 쉬어 이벤트를 Queue에 둔다(in-flight가 "발행됐지만 지워지지 않은" claim으로 차지 않게). */
class ParticipationBridgeAckBackpressureTest {

    @Test
    @SuppressWarnings("unchecked")
    void pausesClaimWhileAckBacklogIsHighAndResumesAfterDrain() throws Exception {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        BridgeQueueService queueService = mock(BridgeQueueService.class);
        RedisStockService stockService = mock(RedisStockService.class);
        JsonMapper jsonMapper = mock(JsonMapper.class);
        SetOperations<String, String> setOps = mock(SetOperations.class);
        ListOperations<String, String> listOps = mock(ListOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members("active:campaigns")).thenReturn(Set.of("7"));
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.size("queue:campaign:{7}")).thenReturn(1L); // 회차당 claim 한도 500
        when(stockService.isActive(7L)).thenReturn(true);
        when(jsonMapper.readValue(anyString(), any(Class.class))).thenReturn(new ParticipationEvent(7L, 1L, 1L));
        AtomicInteger claims = new AtomicInteger();
        when(queueService.claim(7L)).thenAnswer(inv -> {
            int n = claims.incrementAndGet();
            return new BridgeQueueService.Claim(7L, "t" + n + "|m", "m");
        });
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));

        // ACK drainer를 실행하지 않고 쌓아 둔다.
        List<Runnable> drainers = new ArrayList<>();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ParticipationBridge bridge = new ParticipationBridge(redisTemplate, kafkaTemplate, queueService,
                drainers::add, stockService, registry, jsonMapper);

        int rounds = ParticipationBridge.ACK_BACKLOG_PAUSE / 500;
        for (int i = 0; i < rounds; i++) {
            bridge.drainQueues();
        }
        assertThat(claims.get()).isEqualTo(ParticipationBridge.ACK_BACKLOG_PAUSE);

        bridge.drainQueues(); // 대기 ACK 5,000 → claim 쉼
        assertThat(claims.get()).isEqualTo(ParticipationBridge.ACK_BACKLOG_PAUSE);
        assertThat(registry.counter("bridge.claim.skipped", "reason", "ack_backlog").count()).isEqualTo(1);

        drainers.forEach(Runnable::run); // ACK 처리 → 대기 0
        bridge.drainQueues();
        assertThat(claims.get()).isEqualTo(ParticipationBridge.ACK_BACKLOG_PAUSE + 500);
    }
}
