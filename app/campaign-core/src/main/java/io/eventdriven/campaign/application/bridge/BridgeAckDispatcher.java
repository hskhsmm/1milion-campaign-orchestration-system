package io.eventdriven.campaign.application.bridge;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Kafka 성공이 확인된 claim의 Redis ACK를 모아서 캠페인별 ZREM 한 번으로 처리한다.
 *
 * <p>claim마다 실행기 작업을 만들면 ACK 처리량이 "스레드 수 / Redis 왕복 시간"으로 묶여, claim이 빨라질 때
 * 실행기 큐가 넘치고 ACK가 거부된다(2026-11-04 운영 N=10: 거부 7,157건 → lease 만료 대기·중복 발행).
 * 여기서는 claim을 대기열에 넣기만 하고, 실행기에는 대기열을 비우는 작업(drainer)을 최대 {@code maxDrainers}개만 둔다.
 *
 * <p>정합성: ACK가 거부·실패·유실(앱 종료)돼도 claim은 in-flight에 남아 lease 만료 후 reclaim된다.
 * 기존 단건 ACK 실패와 같은 복구 경로이며, 새 유실 경로를 만들지 않는다. 중복 발행은 Consumer·DB 멱등 처리에 맡긴다.
 */
@Slf4j
final class BridgeAckDispatcher {

    /** 한 번의 ZREM에 넣는 최대 member 수. */
    static final int MAX_ACK_BATCH = 100;
    /** 대기 ACK 상한. 넘치면 거부하고 claim을 reclaim에 맡긴다. 캠페인당 in-flight 상한(10,000)보다 크게 둔다. */
    static final int MAX_PENDING = 20_000;

    private final BridgeQueueService bridgeQueueService;
    private final Executor executor;
    private final int maxDrainers;
    private final int maxPending;
    private final ConcurrentLinkedQueue<BridgeQueueService.Claim> pending = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingCount = new AtomicInteger();
    private final AtomicInteger activeDrainers = new AtomicInteger();

    BridgeAckDispatcher(BridgeQueueService bridgeQueueService, Executor executor, int maxDrainers, MeterRegistry registry) {
        this(bridgeQueueService, executor, maxDrainers, MAX_PENDING, registry);
    }

    BridgeAckDispatcher(BridgeQueueService bridgeQueueService, Executor executor, int maxDrainers, int maxPending,
                        MeterRegistry registry) {
        if (maxDrainers < 1 || maxPending < 1) {
            throw new IllegalArgumentException("maxDrainers and maxPending must be >= 1: " + maxDrainers + ", " + maxPending);
        }
        this.bridgeQueueService = bridgeQueueService;
        this.executor = executor;
        this.maxDrainers = maxDrainers;
        this.maxPending = maxPending;
        Gauge.builder("bridge.ack.pending", pendingCount, AtomicInteger::get)
                .description("Kafka-confirmed claims waiting for Redis ACK in this instance")
                .register(registry);
    }

    /** @return false면 대기열이 가득 차 거부됐다(claim은 in-flight에 남아 reclaim 대상). */
    boolean submit(BridgeQueueService.Claim claim) {
        if (pendingCount.incrementAndGet() > maxPending) {
            pendingCount.decrementAndGet();
            return false;
        }
        pending.offer(claim);
        scheduleDrainer();
        return true;
    }

    int pendingCount() {
        return pendingCount.get();
    }

    private void scheduleDrainer() {
        while (true) {
            int active = activeDrainers.get();
            if (active >= maxDrainers) {
                return; // 실행 중인 drainer가 대기열이 빌 때까지 처리한다.
            }
            if (activeDrainers.compareAndSet(active, active + 1)) {
                try {
                    executor.execute(this::drain);
                } catch (RejectedExecutionException rejected) {
                    // 실행기가 종료 중이거나 가득 찼다. 이미 실행 중인 drainer가 있으면 그쪽이 처리한다.
                    activeDrainers.decrementAndGet();
                    log.warn("Bridge ACK drainer rejected; pending ACKs wait for an active drainer or reclaim. pending={}",
                            pendingCount.get());
                }
                return;
            }
        }
    }

    private void drain() {
        try {
            List<BridgeQueueService.Claim> batch = new ArrayList<>(MAX_ACK_BATCH);
            while (true) {
                batch.clear();
                BridgeQueueService.Claim claim;
                while (batch.size() < MAX_ACK_BATCH && (claim = pending.poll()) != null) {
                    batch.add(claim);
                }
                if (batch.isEmpty()) {
                    return;
                }
                pendingCount.addAndGet(-batch.size());
                acknowledge(batch);
            }
        } finally {
            activeDrainers.decrementAndGet();
            // 종료 직전에 들어온 claim이 drainer 없이 남지 않도록 다시 확인한다.
            if (!pending.isEmpty()) {
                scheduleDrainer();
            }
        }
    }

    private void acknowledge(List<BridgeQueueService.Claim> batch) {
        Map<Long, List<BridgeQueueService.Claim>> byCampaign = new LinkedHashMap<>();
        for (BridgeQueueService.Claim claim : batch) {
            byCampaign.computeIfAbsent(claim.campaignId(), id -> new ArrayList<>()).add(claim);
        }
        byCampaign.forEach((campaignId, claims) -> {
            try {
                if (claims.size() == 1) {
                    if (!bridgeQueueService.acknowledge(claims.get(0))) {
                        log.warn("Kafka succeeded after claim was reclaimed; duplicate may be retried. campaignId={}", campaignId);
                    }
                    return;
                }
                long removed = bridgeQueueService.acknowledgeBatch(campaignId, claims);
                if (removed < claims.size()) {
                    log.warn("Kafka succeeded after some claims were reclaimed; duplicates may be retried. campaignId={}, notFound={}",
                            campaignId, claims.size() - removed);
                }
            } catch (Exception ackFailure) {
                log.error("Kafka succeeded but Redis claim ack failed; duplicates may be retried. campaignId={}, count={}",
                        campaignId, claims.size(), ackFailure);
            }
        });
    }
}
