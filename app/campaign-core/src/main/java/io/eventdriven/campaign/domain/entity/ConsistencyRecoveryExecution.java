package io.eventdriven.campaign.domain.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "consistency_recovery_execution")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConsistencyRecoveryExecution extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "requested_by", length = 100)
    private String requestedBy;

    @Column(name = "dry_run", nullable = false)
    private boolean dryRun;

    @Column(name = "auto_fix", nullable = false)
    private boolean autoFix;

    @Column(name = "filter_campaign_id")
    private Long filterCampaignId;

    @Column(name = "max_campaigns", nullable = false)
    private int maxCampaigns;

    @Column(name = "target_count", nullable = false)
    private long targetCount;

    @Column(name = "anomaly_count", nullable = false)
    private long anomalyCount;

    @Column(name = "fixed_count", nullable = false)
    private long fixedCount;

    @Column(name = "report_only_count", nullable = false)
    private long reportOnlyCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ConsistencyRecoveryExecutionStatus status;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    public ConsistencyRecoveryExecution(
            String requestedBy,
            boolean dryRun,
            boolean autoFix,
            Long filterCampaignId,
            int maxCampaigns,
            long targetCount
    ) {
        this.requestedBy = requestedBy;
        this.dryRun = dryRun;
        this.autoFix = autoFix;
        this.filterCampaignId = filterCampaignId;
        this.maxCampaigns = maxCampaigns;
        this.targetCount = targetCount;
        this.status = ConsistencyRecoveryExecutionStatus.REQUESTED;
    }

    public void markRunning(LocalDateTime now) {
        this.status = ConsistencyRecoveryExecutionStatus.RUNNING;
        this.startedAt = now;
        this.endedAt = null;
    }

    public void incrementAnomalyCount() {
        this.anomalyCount++;
    }

    public void incrementFixedCount() {
        this.fixedCount++;
    }

    public void incrementReportOnlyCount() {
        this.reportOnlyCount++;
    }

    public void markCompleted(LocalDateTime now) {
        this.status = ConsistencyRecoveryExecutionStatus.COMPLETED;
        this.endedAt = now;
    }

    public void markFailed(LocalDateTime now) {
        this.status = ConsistencyRecoveryExecutionStatus.FAILED;
        this.endedAt = now;
    }
}
