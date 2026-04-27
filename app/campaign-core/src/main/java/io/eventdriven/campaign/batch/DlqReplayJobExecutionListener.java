package io.eventdriven.campaign.batch;

import io.eventdriven.campaign.application.service.SlackNotificationService;
import io.eventdriven.campaign.application.service.DlqReplayService;
import io.eventdriven.campaign.domain.entity.DlqReplayExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DlqReplayJobExecutionListener implements JobExecutionListener {

    private final DlqReplayService dlqReplayService;
    private final SlackNotificationService slackNotificationService;

    @Override
    public void afterJob(JobExecution jobExecution) {
        if (!"dlqReplayJob".equals(jobExecution.getJobInstance().getJobName())) {
            return;
        }

        Long replayExecutionId = jobExecution.getJobParameters().getLong("replayExecutionId");
        if (replayExecutionId == null) {
            return;
        }

        if (jobExecution.getStatus() == BatchStatus.FAILED) {
            try {
                dlqReplayService.markFailed(replayExecutionId);
            } catch (Exception e) {
                log.error("Failed to mark DLQ replay execution as failed. replayExecutionId={}", replayExecutionId, e);
            }
        }

        sendReplayAlert(jobExecution.getStatus(), replayExecutionId);
    }

    private void sendReplayAlert(BatchStatus batchStatus, Long replayExecutionId) {
        try {
            DlqReplayExecution execution = dlqReplayService.getExecution(replayExecutionId);
            String title = batchStatus == BatchStatus.FAILED
                    ? "DLQ Replay Failed"
                    : "DLQ Replay Completed";
            String detail = String.format(
                    "executionId=%d, status=%s, dryRun=%s, target=%d, replayed=%d, skipped=%d, finalFailed=%d, publishFailed=%d",
                    execution.getId(),
                    execution.getStatus(),
                    execution.isDryRun(),
                    execution.getTargetCount(),
                    execution.getReplayedCount(),
                    execution.getSkippedCount(),
                    execution.getFinalFailedCount(),
                    execution.getPublishFailedCount()
            );
            slackNotificationService.sendBatchAlert(title, detail);
        } catch (Exception e) {
            log.error("Failed to send DLQ replay Slack alert. replayExecutionId={}", replayExecutionId, e);
        }
    }
}
