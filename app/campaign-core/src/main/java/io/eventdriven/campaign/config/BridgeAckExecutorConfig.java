package io.eventdriven.campaign.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class BridgeAckExecutorConfig {

    /** Kafka 완료 스레드에서 동기 Redis ACK를 실행하지 않도록 격리한다. */
    @Bean(name = "bridgeAckExecutor", destroyMethod = "shutdown")
    public ExecutorService bridgeAckExecutor() {
        AtomicInteger threadNumber = new AtomicInteger();
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, "bridge-ack-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2_048), threadFactory, new ThreadPoolExecutor.AbortPolicy());
    }
}
