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
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DlqReplayService {

    private final DlqMessageRepository dlqMessageRepository;
    private final DlqReplayExecutionRepository dlqReplayExecutionRepository;
    private final DlqReplayExecutionItemRepository dlqReplayExecutionItemRepository;
    private final DlqReplayPolicyService dlqReplayPolicyService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final BatchProperties batchProperties;
    private final MeterRegistry meterRegistry;

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
                Boolean.TRUE.equals(dryRun),
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

    @Transactional
    public void processExecution(Long executionId) {
        DlqReplayExecution execution = getExecutionEntity(executionId);
        int pageSize = Math.max(1, batchProperties.getReplay().getPageSize());
        int remaining = execution.getMaxItems();
        long afterId = 0L;
        Set<Long> processedIds = new HashSet<>();

        while (remaining > 0) {
            List<DlqMessageRecord> candidates = dlqMessageRepository.findReplayCandidates(
                    DlqMessageProcessingStatus.PENDING,
                    afterId,
                    execution.getFilterCampaignId(),
                    execution.getFilterReason(),
                    execution.getFilterFromTime(),
                    execution.getFilterToTime(),
                    PageRequest.of(0, Math.min(pageSize, remaining))
            );

            if (candidates.isEmpty()) {
                return;
            }

            for (DlqMessageRecord candidate : candidates) {
                afterId = candidate.getId();
                if (!processedIds.add(candidate.getId())) {
                    continue;
                }
                processSingleMessage(execution, candidate);
                remaining--;
                if (remaining == 0) {
                    return;
                }
            }
        }
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
        return data;
    }

    private void processSingleMessage(DlqReplayExecution execution, DlqMessageRecord message) {
        DlqReplayPolicyService.DlqReplayDecision decision = dlqReplayPolicyService.decide(message);
        LocalDateTime now = LocalDateTime.now();

        if (execution.isDryRun()) {
            recordItem(execution, message, decision, DlqReplayItemResult.DRY_RUN,
                    "Dry run only: " + decision.detail());
            incrementExecutionCounter(execution, decision.action());
            counterFor(decision.action(), true).increment();
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
            kafkaTemplate.send(KafkaConfig.TOPIC_NAME, decision.replayKey(), decision.replayPayload()).get();
            message.incrementReplayAttempt();
            message.markReplayed(now);
            execution.incrementReplayed();
            dlqReplayExecutionItemRepository.save(new DlqReplayExecutionItem(
                    execution,
                    message,
                    decision.reason(),
                    DlqReplayAction.REPLAY,
                    DlqReplayItemResult.SUCCESS,
                    decision.detail(),
                    now
            ));
            counterFor(DlqReplayAction.REPLAY, false).increment();
        } catch (Exception e) {
            message.incrementReplayAttempt();
            execution.incrementPublishFailed();
            dlqReplayExecutionItemRepository.save(new DlqReplayExecutionItem(
                    execution,
                    message,
                    decision.reason(),
                    DlqReplayAction.REPLAY,
                    DlqReplayItemResult.FAILED,
                    e.getMessage(),
                    now
            ));
            counterForFailure().increment();
        }
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
    }

    private void recordItem(
            DlqReplayExecution execution,
            DlqMessageRecord message,
            DlqReplayPolicyService.DlqReplayDecision decision,
            DlqReplayItemResult result,
            String detail
    ) {
        dlqReplayExecutionItemRepository.save(new DlqReplayExecutionItem(
                execution,
                message,
                decision.reason(),
                decision.action(),
                result,
                detail,
                LocalDateTime.now()
        ));
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
