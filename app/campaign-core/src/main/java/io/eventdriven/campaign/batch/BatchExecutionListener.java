package io.eventdriven.campaign.batch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 배치 실행 모니터링 리스너
 * - 배치 시작/종료 로그
 * - 실행 시간 측정
 * - 실패 시 알림 (TODO: Slack, Email 등)
 */
@Slf4j
@Component
public class BatchExecutionListener implements JobExecutionListener {

    private Instant startTime;

    @Override
    public void beforeJob(JobExecution jobExecution) {
        startTime = Instant.now();
        String jobName = jobExecution.getJobInstance().getJobName();
        Long jobExecutionId = jobExecution.getId();

        log.info(" ========================================");
        log.info(" 배치 시작");
        log.info("   Job Name: {}", jobName);
        log.info("   Job Execution ID: {}", jobExecutionId);
        log.info("   Start Time: {}", jobExecution.getStartTime());
        log.info("   Parameters: {}", jobExecution.getJobParameters());
        log.info(" ========================================");
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        Instant endTime = Instant.now();
        Duration duration = Duration.between(startTime, endTime);

        String jobName = jobExecution.getJobInstance().getJobName();
        Long jobExecutionId = jobExecution.getId();
        BatchStatus status = jobExecution.getStatus();
        String exitCode = jobExecution.getExitStatus().getExitCode();
        String exitDescription = jobExecution.getExitStatus().getExitDescription();

        log.info(" ========================================");
        log.info(" 배치 종료");
        log.info("   Job Name: {}", jobName);
        log.info("   Job Execution ID: {}", jobExecutionId);
        log.info("   Status: {}", status);
        log.info("   Exit Code: {}", exitCode);
        log.info("   Exit Description: {}", exitDescription);
        log.info("   Duration: {} 초 ({} ms)", duration.getSeconds(), duration.toMillis());
        log.info("   End Time: {}", jobExecution.getEndTime());
        log.info(" ========================================");

        // 실패 시 알림 전송
        if (status == BatchStatus.FAILED) {
            sendFailureAlert(jobName, jobExecutionId, exitDescription, duration);
        }

        // 성공 시에도 로그 남기기
        if (status == BatchStatus.COMPLETED) {
            log.info(" 배치 성공 - {} ({}초 소요)", jobName, duration.getSeconds());
        }
    }

    /**
     * 배치 실패 시 알림 전송
     * TODO: Slack, Email, SMS 등 실제 알림 구현
     */
    private void sendFailureAlert(String jobName, Long jobExecutionId,
                                   String errorMessage, Duration duration) {
        log.error(" ========================================");
        log.error(" 배치 실패 알림");
        log.error("   Job Name: {}", jobName);
        log.error("   Job Execution ID: {}", jobExecutionId);
        log.error("   Error: {}", errorMessage);
        log.error("   Duration: {} 초", duration.getSeconds());
        log.error(" ========================================");

        // TODO: Slack 웹훅 또는 Email 전송
        // 예시:
        // slackNotifier.send(String.format(
        //     " 배치 실패\nJob: %s\nError: %s\nExecutionId: %d",
        //     jobName, errorMessage, jobExecutionId
        // ));
    }
}
