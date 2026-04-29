package io.eventdriven.campaign.config;

import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Spring Batch 설정
 * - 비동기 JobLauncher: 배치 실행 시 API 응답 지연 방지
 */
@EnableAsync
@Configuration
@SuppressWarnings("removal")
public class BatchConfig {

    /**
     * 비동기 배치 작업용 ThreadPoolTaskExecutor
     * - corePoolSize: 기본 2개 스레드 유지
     * - maxPoolSize: 최대 5개까지 확장
     * - queueCapacity: 대기 큐 크기 10
     * - 프로덕션 환경에서 안정적인 스레드 관리
     */
    @Bean(name = "batchTaskExecutor")
    public ThreadPoolTaskExecutor batchTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("batch-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    @Bean(name = "slackTaskExecutor")
    public ThreadPoolTaskExecutor slackTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("slack-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 비동기 JobLauncher
     * - 배치 작업을 백그라운드에서 실행
     * - API는 즉시 jobExecutionId를 반환
     * - 대용량 집계 작업 시 타임아웃 방지
     * - ThreadPoolTaskExecutor 사용으로 OOM 방지
     */
    @Primary
    @Bean(name = "asyncJobLauncher")
    public JobLauncher asyncJobLauncher(
            JobRepository jobRepository,
            @Qualifier("batchTaskExecutor") ThreadPoolTaskExecutor batchTaskExecutor
    ) throws Exception {
        TaskExecutorJobLauncher jobLauncher = new TaskExecutorJobLauncher();
        jobLauncher.setJobRepository(jobRepository);
        jobLauncher.setTaskExecutor(batchTaskExecutor);
        jobLauncher.afterPropertiesSet();
        return jobLauncher;
    }
}
