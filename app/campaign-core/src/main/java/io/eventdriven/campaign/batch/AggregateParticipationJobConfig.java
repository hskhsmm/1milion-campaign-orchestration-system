package io.eventdriven.campaign.batch;


import io.eventdriven.campaign.application.service.CampaignAggregationService;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class AggregateParticipationJobConfig {

    @Bean
    public Tasklet aggregateByWindowTasklet(CampaignAggregationService campaignAggregationService) {
        return new AggregateParticipationTasklet(campaignAggregationService);
    }

    @Bean
    public Step aggregateByWindowStep(JobRepository jobRepository,
                                      PlatformTransactionManager transactionManager,
                                      Tasklet aggregateByWindowTasklet) {
        return new StepBuilder("aggregateByWindow", jobRepository)
                .tasklet(aggregateByWindowTasklet, transactionManager)
                .build();
    }

    @Bean
    public Job aggregateParticipationJob(JobRepository jobRepository,
                                         Step aggregateByWindowStep,
                                         BatchExecutionListener batchExecutionListener) {
        return new JobBuilder("aggregateParticipation", jobRepository)
                .start(aggregateByWindowStep)
                .listener(batchExecutionListener)
                .build();
    }
}

