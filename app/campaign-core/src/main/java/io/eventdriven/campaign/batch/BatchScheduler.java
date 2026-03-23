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

/**
 * 배치 작업 스케줄러
 * - 매일 자동으로 전일 집계 실행
 * - 매주 메타데이터 정리 실행
 */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressWarnings("removal")
public class BatchScheduler {

    @Qualifier("asyncJobLauncher")
    private final JobLauncher asyncJobLauncher;

    private final Job aggregateParticipationJob;
    private final Job batchMetadataCleanupJob;

    /**
     * 매일 새벽 2시에 전일 데이터 집계
     * - 전일(어제) 참여 이력을 campaign_stats에 집계
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void scheduleDailyAggregation() {
        try {
            LocalDate yesterday = LocalDate.now().minusDays(1);

            JobParameters params = new JobParametersBuilder()
                    .addString("date", yesterday.toString())
                    .addLong("ts", System.currentTimeMillis())
                    .toJobParameters();

            JobExecution execution = asyncJobLauncher.run(aggregateParticipationJob, params);

            log.info(" 일일 집계 배치 실행 완료 - jobExecutionId: {}, date: {}",
                    execution.getId(), yesterday);

        } catch (JobExecutionAlreadyRunningException e) {
            log.warn(" 일일 집계 배치가 이미 실행 중입니다.", e);

        } catch (JobRestartException e) {
            log.error(" 일일 집계 배치 재시작 실패", e);

        } catch (JobInstanceAlreadyCompleteException e) {
            log.warn(" 일일 집계가 이미 완료되었습니다.", e);

        } catch (InvalidJobParametersException e) {
            log.error(" 잘못된 배치 파라미터", e);

        } catch (Exception e) {
            log.error(" 일일 집계 배치 실행 중 예상치 못한 오류 발생", e);
            // TODO: 알림 전송 (Slack, Email 등)
        }
    }

    /**
     * 매주 일요일 새벽 3시에 배치 메타데이터 정리
     * - 90일 이상 오래된 배치 실행 이력 삭제
     */
    @Scheduled(cron = "0 0 3 * * SUN")
    public void scheduleMetadataCleanup() {
        try {
            JobParameters params = new JobParametersBuilder()
                    .addLong("ts", System.currentTimeMillis())
                    .toJobParameters();

            JobExecution execution = asyncJobLauncher.run(batchMetadataCleanupJob, params);

            log.info(" 메타데이터 정리 배치 실행 완료 - jobExecutionId: {}",
                    execution.getId());

        } catch (JobExecutionAlreadyRunningException e) {
            log.warn(" 메타데이터 정리 배치가 이미 실행 중입니다.", e);

        } catch (JobRestartException e) {
            log.error(" 메타데이터 정리 배치 재시작 실패", e);

        } catch (JobInstanceAlreadyCompleteException e) {
            log.warn(" 메타데이터 정리가 이미 완료되었습니다.", e);

        } catch (InvalidJobParametersException e) {
            log.error(" 잘못된 배치 파라미터", e);

        } catch (Exception e) {
            log.error(" 메타데이터 정리 배치 실행 중 예상치 못한 오류 발생", e);
            // TODO: 알림 전송 (Slack, Email 등)
        }
    }
}
