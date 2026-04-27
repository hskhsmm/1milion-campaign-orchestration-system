package io.eventdriven.campaign.batch;

import io.eventdriven.campaign.application.service.DlqReplayService;
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
public class DlqReplayJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final DlqReplayService dlqReplayService;
    private final DlqReplayJobExecutionListener dlqReplayJobExecutionListener;

    @Bean
    public Job dlqReplayJob(
            Step dlqReplayStartStep,
            Step dlqReplayProcessStep,
            Step dlqReplayFinishStep
    ) {
        return new JobBuilder("dlqReplayJob", jobRepository)
                .listener(dlqReplayJobExecutionListener)
                .start(dlqReplayStartStep)
                .next(dlqReplayProcessStep)
                .next(dlqReplayFinishStep)
                .build();
    }

    @Bean
    public Step dlqReplayStartStep() {
        return new StepBuilder("dlqReplayStartStep", jobRepository)
                .tasklet(markRunningTasklet(), transactionManager)
                .build();
    }

    @Bean
    public Step dlqReplayProcessStep() {
        return new StepBuilder("dlqReplayProcessStep", jobRepository)
                .tasklet(processReplayTasklet(), transactionManager)
                .build();
    }

    @Bean
    public Step dlqReplayFinishStep() {
        return new StepBuilder("dlqReplayFinishStep", jobRepository)
                .tasklet(markCompletedTasklet(), transactionManager)
                .build();
    }

    @Bean
    public Tasklet markRunningTasklet() {
        return (contribution, chunkContext) -> {
            Long executionId = (Long) chunkContext.getStepContext().getJobParameters().get("replayExecutionId");
            dlqReplayService.markRunning(executionId);
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    public Tasklet processReplayTasklet() {
        return (contribution, chunkContext) -> {
            Long executionId = (Long) chunkContext.getStepContext().getJobParameters().get("replayExecutionId");
            dlqReplayService.processExecution(executionId);
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    public Tasklet markCompletedTasklet() {
        return (contribution, chunkContext) -> {
            Long executionId = (Long) chunkContext.getStepContext().getJobParameters().get("replayExecutionId");
            dlqReplayService.markCompleted(executionId);
            return RepeatStatus.FINISHED;
        };
    }
}
