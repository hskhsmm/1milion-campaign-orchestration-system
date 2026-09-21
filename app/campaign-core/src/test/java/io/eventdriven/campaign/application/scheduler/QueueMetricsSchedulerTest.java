package io.eventdriven.campaign.application.scheduler;

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

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueueMetricsSchedulerTest {

    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private SetOperations<String, String> setOperations;
    @Mock private ListOperations<String, String> listOperations;

    private SimpleMeterRegistry meterRegistry;
    private QueueMetricsScheduler scheduler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        scheduler = new QueueMetricsScheduler(redisTemplate, meterRegistry);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
    }

    @Test
    @DisplayName("활성 캠페인의 Redis LLEN을 Gauge에 반영한다")
    void collectQueueSizes_recordsActiveCampaignQueueSize() {
        when(setOperations.members("active:campaigns")).thenReturn(Set.of("61"));
        when(listOperations.size("queue:campaign:{61}")).thenReturn(34_598L);

        scheduler.collectQueueSizes();

        assertThat(queueGauge(61L)).isEqualTo(34_598D);
    }

    @Test
    @DisplayName("캠페인이 active Set에서 제거되면 기존 Gauge를 0으로 갱신한다")
    void collectQueueSizes_zerosGaugeAfterCampaignIsDeactivated() {
        when(setOperations.members("active:campaigns"))
                .thenReturn(Set.of("61"))
                .thenReturn(Set.of());
        when(listOperations.size("queue:campaign:{61}")).thenReturn(34_598L);

        scheduler.collectQueueSizes();
        scheduler.collectQueueSizes();

        assertThat(queueGauge(61L)).isZero();
    }

    @Test
    @DisplayName("다른 캠페인이 활성 상태여도 종료된 캠페인의 Gauge는 0이 된다")
    void collectQueueSizes_zerosOnlyInactiveCampaignGauges() {
        when(setOperations.members("active:campaigns"))
                .thenReturn(Set.of("61", "62"))
                .thenReturn(Set.of("62"));
        when(listOperations.size("queue:campaign:{61}")).thenReturn(100L);
        when(listOperations.size("queue:campaign:{62}"))
                .thenReturn(200L)
                .thenReturn(50L);

        scheduler.collectQueueSizes();
        scheduler.collectQueueSizes();

        assertThat(queueGauge(61L)).isZero();
        assertThat(queueGauge(62L)).isEqualTo(50D);
    }

    private double queueGauge(Long campaignId) {
        return meterRegistry.get("redis.queue.size")
                .tag("campaignId", String.valueOf(campaignId))
                .gauge()
                .value();
    }
}
