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

    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private ZSetOperations<String, String> zSetOperations;

    @Test
    void claimUsesSameClusterSlotAndPreservesOriginalMessage() {
        String message = "{\"campaignId\":91,\"payload\":\"a|b\"}";
        List<String> keys = List.of("queue:campaign:{91}", "bridge:inflight:campaign:{91}");
        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(keys), anyString(), eq("10000")))
                .thenReturn("unique-token|" + message);

        BridgeQueueService.Claim claim = new BridgeQueueService(redisTemplate).claim(91L);

        assertThat(claim.message()).isEqualTo(message);
        assertThat(claim.value()).isEqualTo("unique-token|" + message);
    }

    @Test
    void acknowledgeRemovesOnlyThatClaim() {
        BridgeQueueService.Claim claim = new BridgeQueueService.Claim(91L, "id|message", "message");
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.remove("bridge:inflight:campaign:{91}", "id|message")).thenReturn(1L);

        assertThat(new BridgeQueueService(redisTemplate).acknowledge(claim)).isTrue();
        verify(zSetOperations).remove("bridge:inflight:campaign:{91}", "id|message");
    }

    @Test
    void reclaimUsesSameClusterSlot() {
        List<String> keys = List.of("queue:campaign:{91}", "bridge:inflight:campaign:{91}");
        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(keys), eq("300000"), eq("500")))
                .thenReturn(3L);

        assertThat(new BridgeQueueService(redisTemplate).reclaimExpired(91L)).isEqualTo(3L);
    }
}
