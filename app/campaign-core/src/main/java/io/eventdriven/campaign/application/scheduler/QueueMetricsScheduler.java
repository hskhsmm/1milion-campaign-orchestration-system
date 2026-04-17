package io.eventdriven.campaign.application.scheduler;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis Queue 적재량 메트릭 수집 스케줄러 (이슈 #23)
 *
 * 10초 주기로 active:campaigns를 순회하며 각 캠페인 Queue의 LLEN을 Gauge로 기록.
 * Gauge는 현재 시점의 값을 반환하는 메트릭 → Queue 적재량 모니터링에 적합.
 *
 * Prometheus 메트릭명: redis_queue_size{campaignId="1"}
 *
 * Gauge 사용 이유:
 * - Counter: 누적값만 증가 (Queue 크기 표현 불가)
 * - Timer: 실행 시간 측정용
 * - Gauge: 현재 값을 조회해서 반환 → LLEN 같은 현재 상태 측정에 적합
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueueMetricsScheduler {

    private final RedisTemplate<String, String> redisTemplate;
    private final MeterRegistry meterRegistry;

    private static final String ACTIVE_CAMPAIGNS_KEY = "active:campaigns";
    private static final String QUEUE_KEY_PREFIX = "queue:campaign:";

    // campaignId별 현재 Queue 크기 저장소
    // Gauge는 이 Map의 값을 주기적으로 읽어서 Prometheus에 노출
    private final Map<Long, Long> queueSizes = new ConcurrentHashMap<>();

    /**
     * 10초마다 실행 — active:campaigns 순회 → LLEN 조회 → Gauge 갱신
     *
     * Gauge 등록 방식:
     * - 최초 campaignId 등장 시 Gauge 등록 (queueSizes Map의 값을 참조)
     * - 이후 매 10초마다 queueSizes 값만 갱신 → Gauge가 자동으로 최신값 반환
     */
    @Scheduled(fixedDelay = 10_000)
    public void collectQueueSizes() {
        Set<String> campaignIds = redisTemplate.opsForSet().members(ACTIVE_CAMPAIGNS_KEY);
        if (campaignIds == null || campaignIds.isEmpty()) {
            return;
        }

        for (String campaignIdStr : campaignIds) {
            try {
                Long campaignId = Long.parseLong(campaignIdStr);
                String queueKey = QUEUE_KEY_PREFIX + campaignId;

                Long size = redisTemplate.opsForList().size(queueKey);
                long queueSize = size != null ? size : 0L;

                // 첫 등장 시 Gauge 등록
                // Gauge는 람다로 queueSizes.get(campaignId)를 참조 → 값이 바뀌면 자동 반영
                queueSizes.computeIfAbsent(campaignId, id -> {
                    Gauge.builder("redis.queue.size", queueSizes, m -> m.getOrDefault(id, 0L).doubleValue())
                            .description("Redis Queue 현재 적재량")
                            .tag("campaignId", String.valueOf(id))
                            .register(meterRegistry);
                    return 0L;
                });

                // 현재 LLEN 값으로 갱신 → Gauge가 다음 스크래핑 시 이 값을 반환
                queueSizes.put(campaignId, queueSize);

                log.debug("Queue 적재량 수집. campaignId={}, size={}", campaignId, queueSize);
            } catch (Exception e) {
                log.error("Queue 메트릭 수집 실패. campaignId={}", campaignIdStr, e);
            }
        }
    }
}
