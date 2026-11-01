package io.eventdriven.campaign.application.service;

import io.eventdriven.campaign.config.BatchProperties;
import io.eventdriven.campaign.config.KafkaConfig;
import io.eventdriven.campaign.domain.entity.DlqMessageProcessingStatus;
import io.eventdriven.campaign.domain.entity.DlqMessageRecord;
import io.eventdriven.campaign.domain.entity.DlqReplayAction;
import io.eventdriven.campaign.domain.entity.DlqReplayExecution;
import io.eventdriven.campaign.domain.entity.DlqReplayExecutionItem;
import io.eventdriven.campaign.domain.entity.DlqReplayExecutionStatus;
import io.eventdriven.campaign.domain.entity.DlqReplayItemResult;
import io.eventdriven.campaign.domain.repository.DlqMessageRepository;
import io.eventdriven.campaign.domain.repository.DlqReplayExecutionItemRepository;
import io.eventdriven.campaign.domain.repository.DlqReplayExecutionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class DlqReplayService {

    private final DlqMessageRepository dlqMessageRepository;
    private final DlqReplayExecutionRepository dlqReplayExecutionRepository;
    private final DlqReplayExecutionItemRepository dlqReplayExecutionItemRepository;
    private final DlqReplayPolicyService dlqReplayPolicyService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final BatchProperties batchProperties;
    private final MeterRegistry meterRegistry;

    public DlqReplayService(DlqMessageRepository messages, DlqReplayExecutionRepository executions,
            DlqReplayExecutionItemRepository items, DlqReplayPolicyService policy,
            @org.springframework.beans.factory.annotation.Qualifier("dlqReplayKafkaTemplate") KafkaTemplate<String,String> kafka,
            BatchProperties properties, MeterRegistry registry) {
        this.dlqMessageRepository = messages;
        this.dlqReplayExecutionRepository = executions;
        this.dlqReplayExecutionItemRepository = items;
        this.dlqReplayPolicyService = policy;
        this.kafkaTemplate = kafka;
        this.batchProperties = properties;
        this.meterRegistry = registry;
    }

    @Transactional
    public DlqReplayExecution createExecution(
            String requestedBy,
            Boolean dryRun,
            Long campaignId,
            String reason,
            LocalDateTime fromTime,
            LocalDateTime toTime,
            Integer maxItems
    ) {
        toTime = toTime != null ? toTime : LocalDateTime.now();
        int effectiveMaxItems = maxItems != null && maxItems > 0
                ? maxItems
                : batchProperties.getReplay().getDefaultMaxItems();
        long targetCount = dlqMessageRepository.countReplayCandidates(
                DlqMessageProcessingStatus.PENDING,
                campaignId,
                normalize(reason),
                fromTime,
                toTime
        );
        DlqReplayExecution execution = new DlqReplayExecution(
                normalize(requestedBy),
                !Boolean.FALSE.equals(dryRun),
                campaignId,
                normalize(reason),
                fromTime,
                toTime,
                effectiveMaxItems,
                Math.min(targetCount, effectiveMaxItems)
        );
        return dlqReplayExecutionRepository.save(execution);
    }

    @Transactional
    public void markRunning(Long executionId) {
        DlqReplayExecution execution = getExecutionEntity(executionId);
        execution.markRunning(LocalDateTime.now());
    }

    @Transactional
    public void markCompleted(Long executionId) {
        DlqReplayExecution execution = getExecutionEntity(executionId);
        if (execution.getStatus() != DlqReplayExecutionStatus.FAILED) {
            execution.markCompleted(LocalDateTime.now());
        }
    }

    @Transactional
    public void markFailed(Long executionId) {
        DlqReplayExecution execution = getExecutionEntity(executionId);
        execution.markFailed(LocalDateTime.now());
    }

    /** One tasklet invocation = one committed item. Cursor and item outcome commit together.
     * A row lock serializes competing executions; replay send blocks up to 1s, then future wait up to 5s.
     * REQUIRES_NEW preserves failure evidence when the surrounding Tasklet transaction rolls back.
     * Broker success followed by DB rollback can republish, so Consumer remains idempotent.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public boolean processNext(Long executionId) {
        DlqReplayExecution execution = dlqReplayExecutionRepository.findLockedById(executionId)
                .orElseThrow(() -> new IllegalArgumentException("Execution not found: " + executionId));
        if (execution.getProcessedCount() >= execution.getMaxItems()) return false;
        List<Long> ids = dlqMessageRepository.findReplayCandidateIds(
                DlqMessageProcessingStatus.PENDING, execution.getLastMessageId(),
                execution.getFilterCampaignId(), execution.getFilterReason(),
                execution.getFilterFromTime(), execution.getFilterToTime(), PageRequest.of(0, 1));
        if (ids.isEmpty()) return false;
        DlqMessageRecord message = dlqMessageRepository.findLockedById(ids.get(0)).orElseThrow();
        // Re-read under lock; a competing execution may have finished while we waited.
        if (message.getProcessingStatus() == DlqMessageProcessingStatus.PENDING) {
            processSingleMessage(execution, message);
            // Commit the failure evidence, but keep the original and cursor for resume.
            if (execution.getStatus() == DlqReplayExecutionStatus.FAILED) return false;
            execution.advance(message.getId());
        } else {
            execution.moveCursor(message.getId());
        }
        return true;
    }

    @Transactional(readOnly = true)
    public DlqReplayExecution getExecution(Long executionId) {
        return getExecutionEntity(executionId);
    }

    @Transactional(readOnly = true)
    public List<DlqReplayExecutionItem> getExecutionItems(Long executionId) {
        return dlqReplayExecutionItemRepository.findByExecutionIdOrderByIdAsc(
                executionId,
                PageRequest.of(0, 100)
        );
    }

    @Transactional(readOnly = true)
    public List<DlqMessageRecord> searchMessages(
            DlqMessageProcessingStatus status,
            Long campaignId,
            String reason,
            int size
    ) {
        int safeSize = Math.max(1, size);
        return dlqMessageRepository.searchMessages(
                status,
                campaignId,
                normalize(reason),
                PageRequest.of(0, safeSize)
        );
    }

    public Map<String, Object> toExecutionResponse(DlqReplayExecution execution, List<DlqReplayExecutionItem> items) {
        Map<String, Object> data = new HashMap<>();
        data.put("execution", execution);
        data.put("items", items);
        data.put("completionMeaning", "Kafka republication attempted; REPLAYED is not DB confirmation");
        data.put("remainingLimit", Math.max(0, execution.getMaxItems() - execution.getProcessedCount()));
        return data;
    }

    private void processSingleMessage(DlqReplayExecution execution, DlqMessageRecord message) {
        DlqReplayPolicyService.DlqReplayDecision decision = dlqReplayPolicyService.decide(message);
        LocalDateTime now = LocalDateTime.now();

        log.info(
                "DLQ replay decision. executionId={}, messageId={}, classification={}, action={}, publishKey={}, reason={}, detail={}",
                execution.getId(),
                message.getId(),
                message.getReplayClassification(),
                decision.action(),
                decision.replayKey(),
                decision.reason(),
                decision.detail()
        );

        if (execution.isDryRun()) {
            recordItem(execution, message, decision, DlqReplayItemResult.DRY_RUN,
                    "Dry run only: " + decision.detail());
            incrementExecutionCounter(execution, decision.action());
            counterFor(decision.action(), true).increment();
            log.info(
                    "DLQ replay result. executionId={}, messageId={}, classification={}, action={}, publishKey={}, result={}, processingStatus={}, replayAttempts={}",
                    execution.getId(),
                    message.getId(),
                    message.getReplayClassification(),
                    decision.action(),
                    decision.replayKey(),
                    DlqReplayItemResult.DRY_RUN,
                    message.getProcessingStatus(),
                    message.getReplayAttemptCount()
            );
            return;
        }

        switch (decision.action()) {
            case REPLAY -> replay(execution, message, decision, now);
            case SKIP -> skip(execution, message, decision, now);
            case FINAL_FAIL -> finalFail(execution, message, decision, now);
        }
    }

    private void replay(
            DlqReplayExecution execution,
            DlqMessageRecord message,
            DlqReplayPolicyService.DlqReplayDecision decision,
            LocalDateTime now
    ) {
        try {
            kafkaTemplate.send(KafkaConfig.TOPIC_NAME, decision.replayKey(), decision.replayPayload()).get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            message.incrementReplayAttempt();
            execution.incrementPublishFailed();
            execution.markFailed(now);
            recordItem(execution, message, decision, DlqReplayItemResult.FAILED, e.getMessage());
            counterForFailure().increment();
            log.warn(
                    "DLQ replay result. executionId={}, messageId={}, classification={}, action={}, publishKey={}, result={}, processingStatus={}, replayAttempts={}, error={}",
                    execution.getId(),
                    message.getId(),
                    message.getReplayClassification(),
                    decision.action(),
                    decision.replayKey(),
                    DlqReplayItemResult.FAILED,
                    message.getProcessingStatus(),
                    message.getReplayAttemptCount(),
                    e.getMessage()
            );
            return;
        }
        message.incrementReplayAttempt();
        message.markReplayed(now);
        execution.incrementReplayed();
        recordItem(execution, message, decision, DlqReplayItemResult.SUCCESS, decision.detail());
        counterFor(DlqReplayAction.REPLAY, false).increment();
        log.info(
                "DLQ replay result. executionId={}, messageId={}, classification={}, action={}, publishKey={}, result={}, processingStatus={}, replayAttempts={}",
                execution.getId(),
                message.getId(),
                message.getReplayClassification(),
                decision.action(),
                decision.replayKey(),
                DlqReplayItemResult.SUCCESS,
                message.getProcessingStatus(),
                message.getReplayAttemptCount()
        );
    }

    private void skip(
            DlqReplayExecution execution,
            DlqMessageRecord message,
            DlqReplayPolicyService.DlqReplayDecision decision,
            LocalDateTime now
    ) {
        message.markSkipped(decision.reason());
        execution.incrementSkipped();
        recordItem(execution, message, decision, DlqReplayItemResult.SKIPPED, decision.detail());
        counterFor(DlqReplayAction.SKIP, false).increment();
        log.info(
                "DLQ replay result. executionId={}, messageId={}, classification={}, action={}, publishKey={}, result={}, processingStatus={}, replayAttempts={}",
                execution.getId(),
                message.getId(),
                message.getReplayClassification(),
                decision.action(),
                decision.replayKey(),
                DlqReplayItemResult.SKIPPED,
                message.getProcessingStatus(),
                message.getReplayAttemptCount()
        );
    }

    private void finalFail(
            DlqReplayExecution execution,
            DlqMessageRecord message,
            DlqReplayPolicyService.DlqReplayDecision decision,
            LocalDateTime now
    ) {
        message.markFinalFailed(decision.reason());
        execution.incrementFinalFailed();
        recordItem(execution, message, decision, DlqReplayItemResult.FINAL_FAILED, decision.detail());
        counterFor(DlqReplayAction.FINAL_FAIL, false).increment();
        log.info(
                "DLQ replay result. executionId={}, messageId={}, classification={}, action={}, publishKey={}, result={}, processingStatus={}, replayAttempts={}",
                execution.getId(),
                message.getId(),
                message.getReplayClassification(),
                decision.action(),
                decision.replayKey(),
                DlqReplayItemResult.FINAL_FAILED,
                message.getProcessingStatus(),
                message.getReplayAttemptCount()
        );
    }

    private void recordItem(
            DlqReplayExecution execution,
            DlqMessageRecord message,
            DlqReplayPolicyService.DlqReplayDecision decision,
            DlqReplayItemResult result,
            String detail
    ) {
        DlqReplayExecutionItem item = dlqReplayExecutionItemRepository
                .findByExecutionIdAndDlqMessageId(execution.getId(), message.getId())
                .orElseGet(() -> new DlqReplayExecutionItem(execution, message, decision.reason(),
                        decision.action(), result, detail, LocalDateTime.now()));
        item.recordOutcome(decision.reason(), decision.action(), result, detail, LocalDateTime.now());
        dlqReplayExecutionItemRepository.save(item);
    }

    private void incrementExecutionCounter(DlqReplayExecution execution, DlqReplayAction action) {
        switch (action) {
            case REPLAY -> execution.incrementReplayed();
            case SKIP -> execution.incrementSkipped();
            case FINAL_FAIL -> execution.incrementFinalFailed();
        }
    }

    private Counter counterFor(DlqReplayAction action, boolean dryRun) {
        String suffix = switch (action) {
            case REPLAY -> "replayed";
            case SKIP -> "skipped";
            case FINAL_FAIL -> "final_failed";
        };
        return Counter.builder("batch.dlq_replay." + suffix)
                .tag("dryRun", String.valueOf(dryRun))
                .register(meterRegistry);
    }

    private Counter counterForFailure() {
        return Counter.builder("batch.dlq_replay.publish_failed")
                .register(meterRegistry);
    }

    private DlqReplayExecution getExecutionEntity(Long executionId) {
        return dlqReplayExecutionRepository.findById(executionId)
                .orElseThrow(() -> new IllegalArgumentException("DLQ replay execution not found: " + executionId));
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
