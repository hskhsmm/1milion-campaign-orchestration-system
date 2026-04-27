package io.eventdriven.campaign.api.controller;

import io.eventdriven.campaign.api.common.ApiResponse;
import io.eventdriven.campaign.api.dto.request.ConsistencyRecoveryRequest;
import io.eventdriven.campaign.application.service.ConsistencyRecoveryService;
import io.eventdriven.campaign.domain.entity.ConsistencyRecoveryExecution;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/admin/consistency-recovery")
@RequiredArgsConstructor
@SuppressWarnings("removal")
public class ConsistencyRecoveryController {

    @Qualifier("asyncJobLauncher")
    private final JobLauncher asyncJobLauncher;

    @Qualifier("consistencyRecoveryJob")
    private final Job consistencyRecoveryJob;

    private final ConsistencyRecoveryService consistencyRecoveryService;

    @PostMapping
    public ResponseEntity<ApiResponse<?>> run(@RequestBody(required = false) ConsistencyRecoveryRequest request) {
        try {
            ConsistencyRecoveryRequest effectiveRequest = request != null ? request : new ConsistencyRecoveryRequest();
            ConsistencyRecoveryExecution execution = consistencyRecoveryService.createExecution(
                    effectiveRequest.getRequestedBy(),
                    effectiveRequest.getDryRun(),
                    effectiveRequest.getAutoFix(),
                    effectiveRequest.getCampaignId(),
                    effectiveRequest.getMaxCampaigns()
            );

            JobParameters params = new JobParametersBuilder()
                    .addLong("consistencyRecoveryExecutionId", execution.getId())
                    .addLong("ts", System.currentTimeMillis())
                    .toJobParameters();

            JobExecution jobExecution = asyncJobLauncher.run(consistencyRecoveryJob, params);

            Map<String, Object> data = new HashMap<>();
            data.put("jobExecutionId", jobExecution.getId());
            data.put("consistencyRecoveryExecutionId", execution.getId());
            data.put("dryRun", execution.isDryRun());
            data.put("autoFix", execution.isAutoFix());
            data.put("targetCount", execution.getTargetCount());
            data.put("maxCampaigns", execution.getMaxCampaigns());

            return ResponseEntity.ok(ApiResponse.success("Consistency recovery batch started", data));
        } catch (JobExecutionAlreadyRunningException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.fail("Consistency recovery job is already running"));
        } catch (JobRestartException | JobInstanceAlreadyCompleteException | InvalidJobParametersException e) {
            log.error("Failed to start consistency recovery job", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.fail("Failed to start consistency recovery job: " + e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error while starting consistency recovery job", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.fail("Unexpected error: " + e.getMessage()));
        }
    }

    @GetMapping("/executions/{executionId}")
    public ResponseEntity<ApiResponse<?>> getExecution(@PathVariable Long executionId) {
        try {
            ConsistencyRecoveryExecution execution = consistencyRecoveryService.getExecution(executionId);
            return ResponseEntity.ok(ApiResponse.success(
                    consistencyRecoveryService.toExecutionResponse(
                            execution,
                            consistencyRecoveryService.getExecutionResults(executionId)
                    )
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.fail(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to read consistency recovery execution. executionId={}", executionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.fail("Failed to read execution: " + e.getMessage()));
        }
    }

    @GetMapping("/executions")
    public ResponseEntity<ApiResponse<?>> getExecutions(
            @RequestParam(defaultValue = "20") int size
    ) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    consistencyRecoveryService.getExecutions(Math.min(size, 100))
            ));
        } catch (Exception e) {
            log.error("Failed to read consistency recovery executions", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.fail("Failed to read executions: " + e.getMessage()));
        }
    }
}
