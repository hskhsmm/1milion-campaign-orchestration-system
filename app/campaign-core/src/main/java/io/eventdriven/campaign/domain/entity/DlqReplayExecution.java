package io.eventdriven.campaign.domain.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "dlq_replay_execution")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DlqReplayExecution extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "requested_by", length = 100)
    private String requestedBy;

    @Column(name = "dry_run", nullable = false)
    private boolean dryRun;

    @Column(name = "filter_campaign_id")
    private Long filterCampaignId;

    @Column(name = "filter_reason", length = 100)
    private String filterReason;

    @Column(name = "filter_from_time")
    private LocalDateTime filterFromTime;

    @Column(name = "filter_to_time")
    private LocalDateTime filterToTime;

    @Column(name = "max_items", nullable = false)
    private int maxItems;

    @Column(name = "target_count", nullable = false)
    private long targetCount;

    @Column(name = "replayed_count", nullable = false)
    private long replayedCount;

    @Column(name = "skipped_count", nullable = false)
    private long skippedCount;

    @Column(name = "final_failed_count", nullable = false)
    private long finalFailedCount;

    @Column(name = "publish_failed_count", nullable = false)
    private long publishFailedCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private DlqReplayExecutionStatus status;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    public DlqReplayExecution(
            String requestedBy,
            boolean dryRun,
            Long filterCampaignId,
            String filterReason,
            LocalDateTime filterFromTime,
            LocalDateTime filterToTime,
            int maxItems,
            long targetCount
    ) {
        this.requestedBy = requestedBy;
        this.dryRun = dryRun;
        this.filterCampaignId = filterCampaignId;
        this.filterReason = filterReason;
        this.filterFromTime = filterFromTime;
        this.filterToTime = filterToTime;
        this.maxItems = maxItems;
        this.targetCount = targetCount;
        this.status = DlqReplayExecutionStatus.REQUESTED;
    }

    public void markRunning(LocalDateTime now) {
        this.status = DlqReplayExecutionStatus.RUNNING;
        this.startedAt = now;
        this.endedAt = null;
    }

    public void incrementReplayed() {
        this.replayedCount++;
    }

    public void incrementSkipped() {
        this.skippedCount++;
    }

    public void incrementFinalFailed() {
        this.finalFailedCount++;
    }

    public void incrementPublishFailed() {
        this.publishFailedCount++;
    }

    public void markCompleted(LocalDateTime now) {
        this.status = DlqReplayExecutionStatus.COMPLETED;
        this.endedAt = now;
    }

    public void markFailed(LocalDateTime now) {
        this.status = DlqReplayExecutionStatus.FAILED;
        this.endedAt = now;
    }
}
