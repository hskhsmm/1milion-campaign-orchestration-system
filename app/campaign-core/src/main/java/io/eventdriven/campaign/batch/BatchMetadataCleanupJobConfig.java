package io.eventdriven.campaign.batch;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 배치 메타데이터 정리 잡 설정
 * - 90일 이상 오래된 Spring Batch 메타데이터 자동 삭제
 * - 스케줄러를 통해 주기적으로 실행 (예: 매주 일요일)
 */
@Configuration
public class BatchMetadataCleanupJobConfig {

    @Bean
    public Tasklet batchMetadataCleanupTasklet(JdbcTemplate jdbcTemplate) {
        return new BatchMetadataCleanupTasklet(jdbcTemplate);
    }

    @Bean
    public Step batchMetadataCleanupStep(JobRepository jobRepository,
                                         PlatformTransactionManager transactionManager,
                                         Tasklet batchMetadataCleanupTasklet) {
        return new StepBuilder("batchMetadataCleanup", jobRepository)
                .tasklet(batchMetadataCleanupTasklet, transactionManager)
                .build();
    }

    @Bean
    public Job batchMetadataCleanupJob(JobRepository jobRepository,
                                       Step batchMetadataCleanupStep,
                                       BatchExecutionListener batchExecutionListener) {
        return new JobBuilder("batchMetadataCleanup", jobRepository)
                .start(batchMetadataCleanupStep)
                .listener(batchExecutionListener)
                .build();
    }
}
