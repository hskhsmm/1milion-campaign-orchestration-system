package io.eventdriven.campaign.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeAckExecutorConfigTest {

    @Test
    void executorRejectsInsteadOfGrowingWithoutBound() throws Exception {
        ExecutorService executor = new BridgeAckExecutorConfig().bridgeAckExecutor();
        CountDownLatch workersStarted = new CountDownLatch(4);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        try {
            for (int i = 0; i < 4; i++) {
                executor.execute(() -> {
                    workersStarted.countDown();
                    try {
                        releaseWorkers.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertTrue(workersStarted.await(5, TimeUnit.SECONDS));

            for (int i = 0; i < 2_048; i++) {
                executor.execute(() -> { });
            }
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> { }));
        } finally {
            releaseWorkers.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
