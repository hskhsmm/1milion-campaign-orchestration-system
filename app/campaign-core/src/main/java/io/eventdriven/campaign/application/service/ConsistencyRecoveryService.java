package io.eventdriven.campaign.application.service;

import io.eventdriven.campaign.config.BatchProperties;
import io.eventdriven.campaign.domain.entity.*;
import io.eventdriven.campaign.domain.repository.CampaignRepository;
import io.eventdriven.campaign.domain.repository.ConsistencyRecoveryExecutionRepository;
import io.eventdriven.campaign.domain.repository.ConsistencyRecoveryResultRepository;
import io.eventdriven.campaign.domain.repository.ParticipationHistoryRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConsistencyRecoveryService {

    private final CampaignRepository campaignRepository;
    private final ParticipationHistoryRepository participationHistoryRepository;
    private final ConsistencyRecoveryExecutionRepository executionRepository;
    private final ConsistencyRecoveryResultRepository resultRepository;
    private final RedisStockService redisStockService;
    private final RedisQueueService redisQueueService;
    private final BatchProperties batchProperties;
    private final MeterRegistry meterRegistry;

    @Transactional
    public ConsistencyRecoveryExecution createExecution(
            String requestedBy,
            Boolean dryRun,
            Boolean autoFix,
            Long campaignId,
            Integer maxCampaigns
    ) {
        int effectiveMaxCampaigns = maxCampaigns != null && maxCampaigns > 0
                ? maxCampaigns
                : batchProperties.getConsistency().getDefaultMaxCampaigns();
        long targetCount = resolveTargetCount(campaignId, effectiveMaxCampaigns);
        return executionRepository.save(new ConsistencyRecoveryExecution(
                normalize(requestedBy),
                Boolean.TRUE.equals(dryRun),
                Boolean.TRUE.equals(autoFix),
                campaignId,
                effectiveMaxCampaigns,
                targetCount
        ));
    }

    @Transactional
    public void markRunning(Long executionId) {
        getExecutionEntity(executionId).markRunning(LocalDateTime.now());
    }

    @Transactional
    public void markCompleted(Long executionId) {
        ConsistencyRecoveryExecution execution = getExecutionEntity(executionId);
        if (execution.getStatus() != ConsistencyRecoveryExecutionStatus.FAILED) {
            execution.markCompleted(LocalDateTime.now());
        }
    }

    @Transactional
    public void markFailed(Long executionId) {
        getExecutionEntity(executionId).markFailed(LocalDateTime.now());
    }

    @Transactional
    public void processExecution(Long executionId) {
        ConsistencyRecoveryExecution execution = getExecutionEntity(executionId);
        List<Campaign> campaigns = loadTargetCampaigns(execution);

        for (Campaign campaign : campaigns) {
            inspectCampaign(execution, campaign);
        }
    }

    @Transactional(readOnly = true)
    public ConsistencyRecoveryExecution getExecution(Long executionId) {
        return getExecutionEntity(executionId);
    }

    @Transactional(readOnly = true)
    public List<ConsistencyRecoveryResult> getExecutionResults(Long executionId) {
        return resultRepository.findByExecutionIdOrderByIdAsc(executionId, PageRequest.of(0, 200));
    }

    @Transactional(readOnly = true)
    public List<ConsistencyRecoveryExecution> getExecutions(int size) {
        return executionRepository.findByOrderByIdDesc(PageRequest.of(0, Math.max(1, size)));
    }

    public Map<String, Object> toExecutionResponse(
            ConsistencyRecoveryExecution execution,
            List<ConsistencyRecoveryResult> results
    ) {
        Map<String, Object> data = new HashMap<>();
        data.put("execution", execution);
        data.put("results", results);
        return data;
    }

    private void inspectCampaign(ConsistencyRecoveryExecution execution, Campaign campaign) {
        long successCount = nvl(participationHistoryRepository.countByCampaignIdAndStatus(
                campaign.getId(), ParticipationStatus.SUCCESS));
        long pendingCount = nvl(participationHistoryRepository.countByCampaignIdAndStatus(
                campaign.getId(), ParticipationStatus.PENDING));
        Long redisRemainingStock = redisStockService.getStock(campaign.getId());
        Long redisTotalStock = redisStockService.getTotal(campaign.getId());
        long queueSize = nvl(redisQueueService.size(campaign.getId()));
        boolean activeFlagPresent = redisStockService.isActive(campaign.getId());
        boolean activeSetPresent = redisStockService.isRegisteredInActiveCampaigns(campaign.getId());

        CampaignRuntimeSnapshot snapshot = new CampaignRuntimeSnapshot(
                campaign,
                successCount,
                pendingCount,
                redisRemainingStock,
                redisTotalStock,
                queueSize,
                activeFlagPresent,
                activeSetPresent
        );

        List<AnomalyDecision> decisions = detectAnomalies(snapshot);
        for (AnomalyDecision decision : decisions) {
            handleDecision(execution, snapshot, decision);
        }
    }

    private List<AnomalyDecision> detectAnomalies(CampaignRuntimeSnapshot snapshot) {
        List<AnomalyDecision> decisions = new ArrayList<>();
        long derivedRemaining = snapshot.derivedRemainingStock();

        if (snapshot.successCount() + snapshot.pendingCount() > snapshot.campaign().getTotalStock()) {
            decisions.add(new AnomalyDecision(
                    ConsistencyAnomalyType.SUCCESS_PENDING_EXCEEDS_TOTAL,
                    ConsistencySeverity.CRITICAL,
                    ConsistencyRecoveryAction.REPORT_ONLY,
                    false,
                    "success + pending exceeds totalStock"
            ));
        }

        if (snapshot.redisRemainingStock() != null && snapshot.redisRemainingStock() < 0) {
            decisions.add(new AnomalyDecision(
                    ConsistencyAnomalyType.NEGATIVE_REDIS_STOCK,
                    ConsistencySeverity.CRITICAL,
                    ConsistencyRecoveryAction.REPORT_ONLY,
                    false,
                    "Redis remaining stock is negative"
            ));
        }

        if (snapshot.campaign().getStatus() == CampaignStatus.OPEN) {
            boolean safeRestore = snapshot.successCount() + snapshot.pendingCount() <= snapshot.campaign().getTotalStock();

            if (snapshot.redisRemainingStock() == null) {
                decisions.add(new AnomalyDecision(
                        ConsistencyAnomalyType.MISSING_REDIS_STOCK,
                        ConsistencySeverity.WARNING,
                        ConsistencyRecoveryAction.RESTORE_REDIS_STATE,
                        safeRestore,
                        "Redis stock key is missing"
                ));
            }

            if (snapshot.redisTotalStock() == null) {
                decisions.add(new AnomalyDecision(
                        ConsistencyAnomalyType.MISSING_REDIS_TOTAL,
                        ConsistencySeverity.WARNING,
                        ConsistencyRecoveryAction.RESTORE_REDIS_STATE,
                        safeRestore,
                        "Redis total key is missing"
                ));
            }

            if (snapshot.redisRemainingStock() != null
                    && safeRestore
                    && snapshot.redisRemainingStock() != derivedRemaining) {
                decisions.add(new AnomalyDecision(
                        ConsistencyAnomalyType.REDIS_REMAINING_MISMATCH,
                        ConsistencySeverity.CRITICAL,
                        ConsistencyRecoveryAction.REPORT_ONLY,
                        false,
                        "Redis remaining stock does not match DB-derived remaining stock"
                ));
            }

            boolean shouldBeActive = derivedRemaining > 0 || snapshot.queueSize() > 0;
            if (shouldBeActive && (!snapshot.activeFlagPresent() || !snapshot.activeSetPresent())) {
                decisions.add(new AnomalyDecision(
                        snapshot.queueSize() > 0
                                ? ConsistencyAnomalyType.QUEUE_WITHOUT_ACTIVE_STATE
                                : ConsistencyAnomalyType.MISSING_ACTIVE_STATE,
                        ConsistencySeverity.WARNING,
                        ConsistencyRecoveryAction.ACTIVATE_CAMPAIGN,
                        true,
                        "Open campaign is not fully active in Redis"
                ));
            }
        }

        if (snapshot.campaign().getStatus() == CampaignStatus.CLOSED) {
            boolean hasRedisResidue = snapshot.redisRemainingStock() != null
                    || snapshot.redisTotalStock() != null
                    || snapshot.activeFlagPresent()
                    || snapshot.activeSetPresent();

            if (hasRedisResidue) {
                boolean safeCleanup = snapshot.queueSize() == 0;
                decisions.add(new AnomalyDecision(
                        ConsistencyAnomalyType.CLOSED_REDIS_RESIDUE,
                        safeCleanup ? ConsistencySeverity.WARNING : ConsistencySeverity.CRITICAL,
                        safeCleanup ? ConsistencyRecoveryAction.CLEANUP_REDIS_STATE : ConsistencyRecoveryAction.REPORT_ONLY,
                        safeCleanup,
                        safeCleanup
                                ? "Closed campaign still has Redis runtime state"
                                : "Closed campaign has Redis runtime state and queued messages"
                ));
            }
        }

        return decisions;
    }

    private void handleDecision(
            ConsistencyRecoveryExecution execution,
            CampaignRuntimeSnapshot snapshot,
            AnomalyDecision decision
    ) {
        Map<String, Object> beforeState = snapshotState(snapshot);
        boolean fixed = false;
        ConsistencyRecoveryAction actionTaken = decision.action();
        String detailMessage = decision.detail();

        if (execution.isDryRun()) {
            actionTaken = decision.action() == ConsistencyRecoveryAction.NONE
                    ? ConsistencyRecoveryAction.NONE
                    : ConsistencyRecoveryAction.REPORT_ONLY;
            detailMessage = "Dry run: " + detailMessage;
        } else if (!execution.isAutoFix() || !decision.fixable()) {
            if (decision.action() != ConsistencyRecoveryAction.REPORT_ONLY
                    && decision.action() != ConsistencyRecoveryAction.NONE) {
                actionTaken = ConsistencyRecoveryAction.REPORT_ONLY;
                detailMessage = detailMessage + " (auto-fix not applied)";
            }
        } else {
            fixed = applyFix(snapshot, decision.action());
            if (!fixed && decision.action() != ConsistencyRecoveryAction.REPORT_ONLY) {
                actionTaken = ConsistencyRecoveryAction.REPORT_ONLY;
                detailMessage = detailMessage + " (auto-fix failed)";
            }
        }

        Map<String, Object> afterState = fixed
                ? refreshRuntimeState(snapshot.campaign().getId(), snapshot)
                : beforeState;

        execution.incrementAnomalyCount();
        if (fixed) {
            execution.incrementFixedCount();
            fixedCounter(decision.anomalyType()).increment();
        } else {
            execution.incrementReportOnlyCount();
            anomalyCounter(decision.anomalyType()).increment();
        }

        log.info(
                "Consistency recovery decision. executionId={}, campaignId={}, anomalyType={}, requestedAction={}, actionTaken={}, fixed={}, before={}, after={}, detail={}",
                execution.getId(),
                snapshot.campaign().getId(),
                decision.anomalyType(),
                decision.action(),
                actionTaken,
                fixed,
                beforeState,
                afterState,
                detailMessage
        );

        resultRepository.save(new ConsistencyRecoveryResult(
                execution,
                snapshot.campaign().getId(),
                snapshot.campaign().getStatus(),
                snapshot.campaign().getTotalStock(),
                snapshot.successCount(),
                snapshot.pendingCount(),
                snapshot.redisRemainingStock(),
                snapshot.redisTotalStock(),
                snapshot.queueSize(),
                snapshot.activeFlagPresent(),
                snapshot.activeSetPresent(),
                decision.anomalyType(),
                decision.severity(),
                actionTaken,
                fixed,
                detailMessage,
                LocalDateTime.now()
        ));
    }

    private boolean applyFix(CampaignRuntimeSnapshot snapshot, ConsistencyRecoveryAction action) {
        try {
            return switch (action) {
                case RESTORE_REDIS_STATE -> restoreRedisState(snapshot);
                case ACTIVATE_CAMPAIGN -> activateCampaign(snapshot);
                case CLEANUP_REDIS_STATE -> cleanupRedisState(snapshot);
                case REPORT_ONLY, NONE -> false;
            };
        } catch (Exception e) {
            return false;
        }
    }

    private boolean restoreRedisState(CampaignRuntimeSnapshot snapshot) {
        long remaining = snapshot.derivedRemainingStock();
        redisStockService.initializeStock(snapshot.campaign().getId(), remaining);
        redisStockService.initializeTotal(snapshot.campaign().getId(), snapshot.campaign().getTotalStock());
        if (remaining > 0 || snapshot.queueSize() > 0) {
            redisStockService.activateCampaign(snapshot.campaign().getId());
        }
        return true;
    }

    private boolean activateCampaign(CampaignRuntimeSnapshot snapshot) {
        redisStockService.activateCampaign(snapshot.campaign().getId());
        return true;
    }

    private boolean cleanupRedisState(CampaignRuntimeSnapshot snapshot) {
        redisStockService.clearRuntimeState(snapshot.campaign().getId());
        return true;
    }

    private List<Campaign> loadTargetCampaigns(ConsistencyRecoveryExecution execution) {
        if (execution.getFilterCampaignId() != null) {
            return campaignRepository.findById(execution.getFilterCampaignId())
                    .map(List::of)
                    .orElseGet(List::of);
        }

        int remaining = execution.getMaxCampaigns();
        int pageSize = Math.max(1, batchProperties.getConsistency().getPageSize());
        int page = 0;
        List<Campaign> campaigns = new ArrayList<>();

        while (remaining > 0) {
            Page<Campaign> result = campaignRepository.findAll(PageRequest.of(
                    page,
                    Math.min(pageSize, remaining),
                    Sort.by(Sort.Direction.ASC, "id")
            ));
            if (result.isEmpty()) {
                break;
            }
            campaigns.addAll(result.getContent());
            remaining = execution.getMaxCampaigns() - campaigns.size();
            page++;
        }

        return campaigns;
    }

    private long resolveTargetCount(Long campaignId, int maxCampaigns) {
        if (campaignId != null) {
            return campaignRepository.existsById(campaignId) ? 1L : 0L;
        }
        return Math.min(campaignRepository.count(), maxCampaigns);
    }

    private ConsistencyRecoveryExecution getExecutionEntity(Long executionId) {
        return executionRepository.findById(executionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Consistency recovery execution not found: " + executionId
                ));
    }

    private long nvl(Long value) {
        return value == null ? 0L : value;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private Counter anomalyCounter(ConsistencyAnomalyType anomalyType) {
        return Counter.builder("batch.consistency_recovery.anomaly")
                .tag("type", anomalyType.name())
                .register(meterRegistry);
    }

    private Counter fixedCounter(ConsistencyAnomalyType anomalyType) {
        return Counter.builder("batch.consistency_recovery.fixed")
                .tag("type", anomalyType.name())
                .register(meterRegistry);
    }

    private Map<String, Object> snapshotState(CampaignRuntimeSnapshot snapshot) {
        Map<String, Object> state = new HashMap<>();
        state.put("campaignStatus", snapshot.campaign().getStatus());
        state.put("totalStock", snapshot.campaign().getTotalStock());
        state.put("successCount", snapshot.successCount());
        state.put("pendingCount", snapshot.pendingCount());
        state.put("derivedRemainingStock", snapshot.derivedRemainingStock());
        state.put("redisRemainingStock", snapshot.redisRemainingStock());
        state.put("redisTotalStock", snapshot.redisTotalStock());
        state.put("queueSize", snapshot.queueSize());
        state.put("activeFlagPresent", snapshot.activeFlagPresent());
        state.put("activeSetPresent", snapshot.activeSetPresent());
        return state;
    }

    private Map<String, Object> refreshRuntimeState(Long campaignId, CampaignRuntimeSnapshot snapshot) {
        Map<String, Object> state = new HashMap<>();
        state.put("campaignStatus", snapshot.campaign().getStatus());
        state.put("totalStock", snapshot.campaign().getTotalStock());
        state.put("successCount", snapshot.successCount());
        state.put("pendingCount", snapshot.pendingCount());
        state.put("derivedRemainingStock", snapshot.derivedRemainingStock());
        state.put("redisRemainingStock", redisStockService.getStock(campaignId));
        state.put("redisTotalStock", redisStockService.getTotal(campaignId));
        state.put("queueSize", nvl(redisQueueService.size(campaignId)));
        state.put("activeFlagPresent", redisStockService.isActive(campaignId));
        state.put("activeSetPresent", redisStockService.isRegisteredInActiveCampaigns(campaignId));
        return state;
    }

    private record CampaignRuntimeSnapshot(
            Campaign campaign,
            long successCount,
            long pendingCount,
            Long redisRemainingStock,
            Long redisTotalStock,
            long queueSize,
            boolean activeFlagPresent,
            boolean activeSetPresent
    ) {
        private long derivedRemainingStock() {
            return Math.max(campaign.getTotalStock() - successCount - pendingCount, 0L);
        }
    }

    private record AnomalyDecision(
            ConsistencyAnomalyType anomalyType,
            ConsistencySeverity severity,
            ConsistencyRecoveryAction action,
            boolean fixable,
            String detail
    ) {
    }
}
