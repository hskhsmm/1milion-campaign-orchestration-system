package io.eventdriven.campaign.domain.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "consistency_recovery_result")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConsistencyRecoveryResult extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "execution_id", nullable = false)
    private ConsistencyRecoveryExecution execution;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Enumerated(EnumType.STRING)
    @Column(name = "campaign_status", nullable = false, length = 32)
    private CampaignStatus campaignStatus;

    @Column(name = "total_stock", nullable = false)
    private long totalStock;

    @Column(name = "success_count", nullable = false)
    private long successCount;

    @Column(name = "pending_count", nullable = false)
    private long pendingCount;

    @Column(name = "redis_remaining_stock")
    private Long redisRemainingStock;

    @Column(name = "redis_total_stock")
    private Long redisTotalStock;

    @Column(name = "queue_size", nullable = false)
    private long queueSize;

    @Column(name = "active_flag_present", nullable = false)
    private boolean activeFlagPresent;

    @Column(name = "active_set_present", nullable = false)
    private boolean activeSetPresent;

    @Enumerated(EnumType.STRING)
    @Column(name = "anomaly_type", nullable = false, length = 64)
    private ConsistencyAnomalyType anomalyType;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 32)
    private ConsistencySeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_taken", nullable = false, length = 32)
    private ConsistencyRecoveryAction actionTaken;

    @Column(name = "fixed", nullable = false)
    private boolean fixed;

    @Column(name = "detail_message", length = 1000)
    private String detailMessage;

    @Column(name = "checked_at", nullable = false)
    private LocalDateTime checkedAt;

    public ConsistencyRecoveryResult(
            ConsistencyRecoveryExecution execution,
            Long campaignId,
            CampaignStatus campaignStatus,
            long totalStock,
            long successCount,
            long pendingCount,
            Long redisRemainingStock,
            Long redisTotalStock,
            long queueSize,
            boolean activeFlagPresent,
            boolean activeSetPresent,
            ConsistencyAnomalyType anomalyType,
            ConsistencySeverity severity,
            ConsistencyRecoveryAction actionTaken,
            boolean fixed,
            String detailMessage,
            LocalDateTime checkedAt
    ) {
        this.execution = execution;
        this.campaignId = campaignId;
        this.campaignStatus = campaignStatus;
        this.totalStock = totalStock;
        this.successCount = successCount;
        this.pendingCount = pendingCount;
        this.redisRemainingStock = redisRemainingStock;
        this.redisTotalStock = redisTotalStock;
        this.queueSize = queueSize;
        this.activeFlagPresent = activeFlagPresent;
        this.activeSetPresent = activeSetPresent;
        this.anomalyType = anomalyType;
        this.severity = severity;
        this.actionTaken = actionTaken;
        this.fixed = fixed;
        this.detailMessage = detailMessage;
        this.checkedAt = checkedAt;
    }
}
