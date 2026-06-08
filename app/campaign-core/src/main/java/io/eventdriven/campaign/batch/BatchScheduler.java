package io.eventdriven.campaign.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.JobRestartException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Slf4j
@Component
@RequiredArgsConstructor
@SuppressWarnings("removal")
public class BatchScheduler {

    @Qualifier("asyncJobLauncher")
    private final JobLauncher asyncJobLauncher;

    private final Job aggregateParticipationJob;
    private final Job batchMetadataCleanupJob;

    @Scheduled(cron = "0 0 2 * * *")
    public void scheduleDailyAggregation() {
        try {
            LocalDate yesterday = LocalDate.now().minusDays(1);

            JobParameters params = new JobParametersBuilder()
                    .addString("date", yesterday.toString())
                    .addLong("ts", System.currentTimeMillis())
                    .toJobParameters();

            JobExecution execution = asyncJobLauncher.run(aggregateParticipationJob, params);
            log.info("Daily aggregation batch completed. jobExecutionId={}, date={}",
                    execution.getId(), yesterday);
        } catch (JobExecutionAlreadyRunningException e) {
            log.warn("Daily aggregation batch is already running.", e);
        } catch (JobRestartException e) {
            log.error("Failed to restart daily aggregation batch.", e);
        } catch (JobInstanceAlreadyCompleteException e) {
            log.warn("Daily aggregation batch is already complete.", e);
        } catch (InvalidJobParametersException e) {
            log.error("Invalid daily aggregation batch parameters.", e);
        } catch (Exception e) {
            log.error("Unexpected daily aggregation batch error.", e);
        }
    }

    @Scheduled(cron = "0 0 3 * * SUN")
    public void scheduleMetadataCleanup() {
        try {
            JobParameters params = new JobParametersBuilder()
                    .addLong("ts", System.currentTimeMillis())
                    .toJobParameters();

            JobExecution execution = asyncJobLauncher.run(batchMetadataCleanupJob, params);
            log.info("Batch metadata cleanup completed. jobExecutionId={}", execution.getId());
        } catch (JobExecutionAlreadyRunningException e) {
            log.warn("Batch metadata cleanup is already running.", e);
        } catch (JobRestartException e) {
            log.error("Failed to restart batch metadata cleanup.", e);
        } catch (JobInstanceAlreadyCompleteException e) {
            log.warn("Batch metadata cleanup is already complete.", e);
        } catch (InvalidJobParametersException e) {
            log.error("Invalid batch metadata cleanup parameters.", e);
        } catch (Exception e) {
            log.error("Unexpected batch metadata cleanup error.", e);
        }
    }
}
