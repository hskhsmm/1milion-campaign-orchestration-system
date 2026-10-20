package io.eventdriven.campaign.application.bridge;

import io.eventdriven.campaign.application.service.DlqMessageService;
import io.eventdriven.campaign.application.service.RedisStockService;
import io.eventdriven.campaign.application.service.SlackNotificationService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * active Set 제거 가드 검증 (2026-09-25 150만 재테스트 큐 고립 후속)
 *
 * Bridge forwards cleanup to RedisStockService, where the lock and final state check live.
 */
@ExtendWith(MockitoExtension.class)
class ParticipationBridgeDeactivationTest {

    private static final String ACTIVE_CAMPAIGNS_KEY = "active:campaigns";
    private static final String QUEUE_KEY = "queue:campaign:{62}";
    private static final String LEGACY_QUEUE_KEY = "queue:campaign:62";

    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private KafkaTemplate<String, String> kafkaTemplate;
    @Mock private RedisStockService redisStockService;
    @Mock private SlackNotificationService slackNotificationService;
    @Mock private DlqMessageService dlqMessageService;
    @Mock private JsonMapper jsonMapper;
    @Mock private SetOperations<String, String> setOperations;
    @Mock private ListOperations<String, String> listOperations;

    private ParticipationBridge participationBridge;

    @BeforeEach
    void setUp() {
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        participationBridge = new ParticipationBridge(
                redisTemplate,
                kafkaTemplate,
                redisStockService,
                slackNotificationService,
                meterRegistry,
                jsonMapper,
                dlqMessageService
        );

        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members(ACTIVE_CAMPAIGNS_KEY)).thenReturn(Set.of("62"));
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.rightPop(QUEUE_KEY)).thenReturn(null);
    }

    @Test
    @DisplayName("flag 없음 + RPOP null이면 잠금 기반 cleanup을 요청한다")
    void drainQueues_inactiveAndEmpty_requestsCleanup() {
        when(listOperations.size(QUEUE_KEY)).thenReturn(0L);
        when(redisStockService.isActive(62L)).thenReturn(false);

        participationBridge.drainQueues();

        verify(redisStockService).deactivateIfQueueEmpty(62L);
    }

    @Test
    @DisplayName("해시태그 없는 구 큐 키는 더 이상 읽지 않는다")
    void drainQueues_doesNotReadLegacyQueueKey() {
        when(listOperations.size(QUEUE_KEY)).thenReturn(0L);
        when(redisStockService.isActive(62L)).thenReturn(true);

        participationBridge.drainQueues();

        verify(listOperations, never()).rightPop(LEGACY_QUEUE_KEY);
    }

    @Test
    @DisplayName("flag가 살아 있으면 큐가 비어도 active Set에서 제거하지 않는다")
    void drainQueues_activeFlag_keepsActive() {
        when(listOperations.size(QUEUE_KEY)).thenReturn(0L);
        when(redisStockService.isActive(62L)).thenReturn(true);

        participationBridge.drainQueues();

        verify(redisStockService, never()).deactivateIfQueueEmpty(62L);
    }
}
