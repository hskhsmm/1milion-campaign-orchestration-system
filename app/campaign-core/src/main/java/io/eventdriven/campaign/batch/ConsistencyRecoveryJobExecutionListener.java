package io.eventdriven.campaign.batch;

import io.eventdriven.campaign.application.service.ConsistencyRecoveryService;
import io.eventdriven.campaign.application.service.SlackNotificationService;
import io.eventdriven.campaign.domain.entity.ConsistencyRecoveryExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ConsistencyRecoveryJobExecutionListener implements JobExecutionListener {

    private final ConsistencyRecoveryService consistencyRecoveryService;
    private final SlackNotificationService slackNotificationService;

    @Override
    public void afterJob(JobExecution jobExecution) {
        if (!"consistencyRecoveryJob".equals(jobExecution.getJobInstance().getJobName())) {
            return;
        }

        Long executionId = jobExecution.getJobParameters().getLong("consistencyRecoveryExecutionId");
        if (executionId == null) {
            return;
        }

        if (jobExecution.getStatus() == BatchStatus.FAILED) {
            try {
                consistencyRecoveryService.markFailed(executionId);
            } catch (Exception e) {
                log.error("Failed to mark consistency recovery execution as failed. executionId={}", executionId, e);
            }
        }

        sendAlert(executionId, jobExecution.getStatus());
    }

    private void sendAlert(Long executionId, BatchStatus batchStatus) {
        try {
            ConsistencyRecoveryExecution execution = consistencyRecoveryService.getExecution(executionId);
            slackNotificationService.sendBatchAlert(
                    batchStatus == BatchStatus.FAILED
                            ? "Consistency Recovery Failed"
                            : "Consistency Recovery Completed",
                    String.format(
                            "executionId=%d, status=%s, dryRun=%s, autoFix=%s, target=%d, anomalies=%d, fixed=%d, reportOnly=%d",
                            execution.getId(),
                            execution.getStatus(),
                            execution.isDryRun(),
                            execution.isAutoFix(),
                            execution.getTargetCount(),
                            execution.getAnomalyCount(),
                            execution.getFixedCount(),
                            execution.getReportOnlyCount()
                    )
            );
        } catch (Exception e) {
            log.error("Failed to send consistency recovery Slack alert. executionId={}", executionId, e);
        }
    }
}
