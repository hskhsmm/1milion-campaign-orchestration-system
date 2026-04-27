package io.eventdriven.campaign.batch;

import io.eventdriven.campaign.application.service.ConsistencyRecoveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@RequiredArgsConstructor
public class ConsistencyRecoveryJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final ConsistencyRecoveryService consistencyRecoveryService;
    private final ConsistencyRecoveryJobExecutionListener consistencyRecoveryJobExecutionListener;

    @Bean
    public Job consistencyRecoveryJob(
            Step consistencyRecoveryStartStep,
            Step consistencyRecoveryProcessStep,
            Step consistencyRecoveryFinishStep
    ) {
        return new JobBuilder("consistencyRecoveryJob", jobRepository)
                .listener(consistencyRecoveryJobExecutionListener)
                .start(consistencyRecoveryStartStep)
                .next(consistencyRecoveryProcessStep)
                .next(consistencyRecoveryFinishStep)
                .build();
    }

    @Bean
    public Step consistencyRecoveryStartStep() {
        return new StepBuilder("consistencyRecoveryStartStep", jobRepository)
                .tasklet(markRunningTasklet(), transactionManager)
                .build();
    }

    @Bean
    public Step consistencyRecoveryProcessStep() {
        return new StepBuilder("consistencyRecoveryProcessStep", jobRepository)
                .tasklet(processTasklet(), transactionManager)
                .build();
    }

    @Bean
    public Step consistencyRecoveryFinishStep() {
        return new StepBuilder("consistencyRecoveryFinishStep", jobRepository)
                .tasklet(markCompletedTasklet(), transactionManager)
                .build();
    }

    @Bean
    public Tasklet markRunningTasklet() {
        return (contribution, chunkContext) -> {
            Long executionId = (Long) chunkContext.getStepContext()
                    .getJobParameters().get("consistencyRecoveryExecutionId");
            consistencyRecoveryService.markRunning(executionId);
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    public Tasklet processTasklet() {
        return (contribution, chunkContext) -> {
            Long executionId = (Long) chunkContext.getStepContext()
                    .getJobParameters().get("consistencyRecoveryExecutionId");
            consistencyRecoveryService.processExecution(executionId);
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    public Tasklet markCompletedTasklet() {
        return (contribution, chunkContext) -> {
            Long executionId = (Long) chunkContext.getStepContext()
                    .getJobParameters().get("consistencyRecoveryExecutionId");
            consistencyRecoveryService.markCompleted(executionId);
            return RepeatStatus.FINISHED;
        };
    }
}
