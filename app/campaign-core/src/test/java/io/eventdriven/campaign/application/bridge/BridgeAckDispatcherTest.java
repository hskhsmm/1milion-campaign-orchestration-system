package io.eventdriven.campaign.application.bridge;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BridgeAckDispatcherTest {

    private final BridgeQueueService queueService = mock(BridgeQueueService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    /** 실행을 미뤄 두었다가 시험이 직접 돌리는 실행기. */
    private static final class DeferredExecutor implements Executor {
        final Deque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runAll() {
            Runnable task;
            while ((task = tasks.poll()) != null) {
                task.run();
            }
        }
    }

    private static BridgeQueueService.Claim claim(long campaignId, int n) {
        return new BridgeQueueService.Claim(campaignId, "token-" + n + "|m" + n, "m" + n);
    }

    @Test
    void groupsByCampaignAndCapsBatchSize() {
        DeferredExecutor executor = new DeferredExecutor();
        BridgeAckDispatcher dispatcher = new BridgeAckDispatcher(queueService, executor, 1, registry);
        when(queueService.acknowledgeBatch(anyLong(), anyList())).thenAnswer(inv -> (long) inv.<List<?>>getArgument(1).size());

        IntStream.range(0, 150).forEach(i -> dispatcher.submit(claim(1L, i)));
        IntStream.range(0, 70).forEach(i -> dispatcher.submit(claim(2L, i)));
        assertThat(executor.tasks).hasSize(1); // drainer 1개 상한
        assertThat(dispatcher.pendingCount()).isEqualTo(220);

        executor.runAll();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BridgeQueueService.Claim>> batches = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Long> campaigns = ArgumentCaptor.forClass(Long.class);
        verify(queueService, atLeastOnce()).acknowledgeBatch(campaigns.capture(), batches.capture());
        List<Integer> sizes = new ArrayList<>();
        int total = 0;
        for (int i = 0; i < batches.getAllValues().size(); i++) {
            List<BridgeQueueService.Claim> batch = batches.getAllValues().get(i);
            Long campaignId = campaigns.getAllValues().get(i);
            assertThat(batch).allMatch(c -> c.campaignId().equals(campaignId));
            sizes.add(batch.size());
            total += batch.size();
        }
        assertThat(sizes).allMatch(size -> size <= BridgeAckDispatcher.MAX_ACK_BATCH);
        assertThat(total).isEqualTo(220);
        assertThat(dispatcher.pendingCount()).isZero();
        assertThat(registry.get("bridge.ack.pending").gauge().value()).isZero();
    }

    @Test
    void singleClaimUsesSingleAckPath() {
        BridgeAckDispatcher dispatcher = new BridgeAckDispatcher(queueService, Runnable::run, 4, registry);
        BridgeQueueService.Claim only = claim(5L, 1);

        assertThat(dispatcher.submit(only)).isTrue();

        verify(queueService).acknowledge(only);
        verify(queueService, never()).acknowledgeBatch(anyLong(), anyList());
    }

    @Test
    void rejectsWhenPendingLimitReachedAndKeepsCountConsistent() {
        DeferredExecutor executor = new DeferredExecutor();
        BridgeAckDispatcher dispatcher = new BridgeAckDispatcher(queueService, executor, 1, 3, registry);

        assertThat(dispatcher.submit(claim(1L, 1))).isTrue();
        assertThat(dispatcher.submit(claim(1L, 2))).isTrue();
        assertThat(dispatcher.submit(claim(1L, 3))).isTrue();
        assertThat(dispatcher.submit(claim(1L, 4))).isFalse();
        assertThat(dispatcher.pendingCount()).isEqualTo(3);

        executor.runAll();
        assertThat(dispatcher.pendingCount()).isZero();
        assertThat(dispatcher.submit(claim(1L, 5))).isTrue();
    }

    @Test
    void limitsConcurrentDrainers() {
        DeferredExecutor executor = new DeferredExecutor();
        BridgeAckDispatcher dispatcher = new BridgeAckDispatcher(queueService, executor, 3, registry);

        IntStream.range(0, 1_000).forEach(i -> dispatcher.submit(claim(1L, i)));

        assertThat(executor.tasks).hasSize(3);
    }

    @Test
    void ackFailureDoesNotStopDrainingLaterBatches() {
        DeferredExecutor executor = new DeferredExecutor();
        BridgeAckDispatcher dispatcher = new BridgeAckDispatcher(queueService, executor, 1, registry);
        when(queueService.acknowledgeBatch(anyLong(), anyList()))
                .thenThrow(new IllegalStateException("redis down"))
                .thenReturn(100L);

        IntStream.range(0, 200).forEach(i -> dispatcher.submit(claim(1L, i)));
        executor.runAll();

        verify(queueService, times(2)).acknowledgeBatch(eq(1L), anyList());
        assertThat(dispatcher.pendingCount()).isZero(); // 실패분은 Redis in-flight에 남아 reclaim 대상
    }

    @Test
    void submitAfterDrainerFinishedSchedulesNewDrainer() {
        DeferredExecutor executor = new DeferredExecutor();
        BridgeAckDispatcher dispatcher = new BridgeAckDispatcher(queueService, executor, 1, registry);

        dispatcher.submit(claim(1L, 1));
        executor.runAll();
        dispatcher.submit(claim(1L, 2));

        assertThat(executor.tasks).hasSize(1);
        executor.runAll();
        verify(queueService, times(2)).acknowledge(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectedDrainerLeavesClaimsPendingUntilNextSubmit() {
        Executor rejecting = command -> { throw new java.util.concurrent.RejectedExecutionException("shutdown"); };
        BridgeAckDispatcher dispatcher = new BridgeAckDispatcher(queueService, rejecting, 2, registry);

        assertThat(dispatcher.submit(claim(1L, 1))).isTrue();

        assertThat(dispatcher.pendingCount()).isEqualTo(1);
        verify(queueService, never()).acknowledge(org.mockito.ArgumentMatchers.any());
    }
}
