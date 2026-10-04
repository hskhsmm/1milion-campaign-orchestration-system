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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
    @DisplayName("캠페인이 큐를 비우고 active Set에서 제거되면 Gauge가 0이 된다")
    void collectQueueSizes_zerosGaugeAfterCampaignIsDrainedAndDeactivated() {
        when(setOperations.members("active:campaigns"))
                .thenReturn(Set.of("61"))
                .thenReturn(Set.of());
        when(listOperations.size("queue:campaign:{61}"))
                .thenReturn(34_598L)
                .thenReturn(0L);

        scheduler.collectQueueSizes();
        scheduler.collectQueueSizes();

        assertThat(queueGauge(61L)).isZero();
    }

    @Test
    @DisplayName("잔량이 남은 채 active Set에서 제거되면 0이 아닌 실제 LLEN을 보고한다")
    void collectQueueSizes_reportsOrphanedQueueSize() {
        when(setOperations.members("active:campaigns"))
                .thenReturn(Set.of("64"))
                .thenReturn(Set.of());
        when(listOperations.size("queue:campaign:{64}"))
                .thenReturn(211_595L)
                .thenReturn(206_000L);

        scheduler.collectQueueSizes();
        scheduler.collectQueueSizes();

        assertThat(queueGauge(64L)).isEqualTo(206_000D);
    }

    @Test
    @DisplayName("비활성 캠페인은 LLEN 0이 확인된 뒤 더 조회하지 않는다")
    void collectQueueSizes_stopsReadingInactiveQueueAfterEmpty() {
        when(setOperations.members("active:campaigns"))
                .thenReturn(Set.of("61"))
                .thenReturn(Set.of());
        when(listOperations.size("queue:campaign:{61}"))
                .thenReturn(100L)
                .thenReturn(0L);

        scheduler.collectQueueSizes();
        scheduler.collectQueueSizes();
        scheduler.collectQueueSizes();

        verify(listOperations, times(2)).size("queue:campaign:{61}");
        assertThat(queueGauge(61L)).isZero();
    }

    @Test
    @DisplayName("다른 캠페인이 활성 상태여도 비워진 비활성 캠페인의 Gauge는 0이 된다")
    void collectQueueSizes_zerosOnlyDrainedInactiveCampaignGauges() {
        when(setOperations.members("active:campaigns"))
                .thenReturn(Set.of("61", "62"))
                .thenReturn(Set.of("62"));
        when(listOperations.size("queue:campaign:{61}"))
                .thenReturn(100L)
                .thenReturn(0L);
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
