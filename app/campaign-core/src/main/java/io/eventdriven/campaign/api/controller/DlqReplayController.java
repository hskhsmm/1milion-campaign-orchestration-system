package io.eventdriven.campaign.api.controller;

import io.eventdriven.campaign.api.common.ApiResponse;
import io.eventdriven.campaign.api.dto.request.DlqReplayRequest;
import io.eventdriven.campaign.application.service.DlqReplayService;
import io.eventdriven.campaign.domain.entity.DlqMessageProcessingStatus;
import io.eventdriven.campaign.domain.entity.DlqReplayExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.JobRestartException;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/admin/dlq-replay")
@RequiredArgsConstructor
@SuppressWarnings("removal")
public class DlqReplayController {

    @Qualifier("asyncJobLauncher")
    private final JobLauncher asyncJobLauncher;

    @Qualifier("dlqReplayJob")
    private final Job dlqReplayJob;

    private final DlqReplayService dlqReplayService;

    @PostMapping
    public ResponseEntity<ApiResponse<?>> replay(@RequestBody(required = false) DlqReplayRequest request) {
        try {
            DlqReplayRequest effectiveRequest = request != null ? request : new DlqReplayRequest();
            DlqReplayExecution execution = dlqReplayService.createExecution(
                    effectiveRequest.getRequestedBy(),
                    effectiveRequest.getDryRun(),
                    effectiveRequest.getCampaignId(),
                    effectiveRequest.getReason(),
                    effectiveRequest.getFromTime(),
                    effectiveRequest.getToTime(),
                    effectiveRequest.getMaxItems()
            );

            JobParameters params = new JobParametersBuilder()
                    .addLong("replayExecutionId", execution.getId())
                    .addLong("ts", System.currentTimeMillis())
                    .toJobParameters();

            JobExecution jobExecution = asyncJobLauncher.run(dlqReplayJob, params);

            Map<String, Object> data = new HashMap<>();
            data.put("jobExecutionId", jobExecution.getId());
            data.put("replayExecutionId", execution.getId());
            data.put("dryRun", execution.isDryRun());
            data.put("targetCount", execution.getTargetCount());
            data.put("maxItems", execution.getMaxItems());

            return ResponseEntity.ok(ApiResponse.success("DLQ replay batch started", data));
        } catch (JobExecutionAlreadyRunningException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.fail("DLQ replay job is already running"));
        } catch (JobRestartException | JobInstanceAlreadyCompleteException | InvalidJobParametersException e) {
            log.error("Failed to start DLQ replay job", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.fail("Failed to start DLQ replay job: " + e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error while starting DLQ replay job", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.fail("Unexpected error: " + e.getMessage()));
        }
    }

    @GetMapping("/executions/{executionId}")
    public ResponseEntity<ApiResponse<?>> getExecution(@PathVariable Long executionId) {
        try {
            DlqReplayExecution execution = dlqReplayService.getExecution(executionId);
            return ResponseEntity.ok(ApiResponse.success(
                    dlqReplayService.toExecutionResponse(execution, dlqReplayService.getExecutionItems(executionId))
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.fail(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to read DLQ replay execution. executionId={}", executionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.fail("Failed to read execution: " + e.getMessage()));
        }
    }

    @GetMapping("/messages")
    public ResponseEntity<ApiResponse<?>> getMessages(
            @RequestParam(required = false) DlqMessageProcessingStatus status,
            @RequestParam(required = false) Long campaignId,
            @RequestParam(required = false) String reason,
            @RequestParam(defaultValue = "100") int size
    ) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    dlqReplayService.searchMessages(status, campaignId, reason, Math.min(size, 500))
            ));
        } catch (Exception e) {
            log.error("Failed to read DLQ messages", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.fail("Failed to read DLQ messages: " + e.getMessage()));
        }
    }
}
