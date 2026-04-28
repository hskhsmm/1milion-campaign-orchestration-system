package io.eventdriven.campaign.domain.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "dlq_message")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DlqMessageRecord extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32)
    private DlqSourceType sourceType;

    @Column(name = "original_topic", nullable = false)
    private String originalTopic;

    @Column(name = "original_key")
    private String originalKey;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "original_message", nullable = false, columnDefinition = "TEXT")
    private String originalMessage;

    @Column(name = "error_reason", nullable = false, length = 100)
    private String errorReason;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "campaign_id")
    private Long campaignId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "sequence_no")
    private Long sequence;

    @Enumerated(EnumType.STRING)
    @Column(name = "replay_classification", nullable = false, length = 32)
    private DlqReplayClassification replayClassification;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 32)
    private DlqMessageProcessingStatus processingStatus;

    @Column(name = "replay_attempt_count", nullable = false)
    private int replayAttemptCount;

    @Column(name = "final_failure_reason", length = 255)
    private String finalFailureReason;

    @Column(name = "last_replayed_at")
    private LocalDateTime lastReplayedAt;

    public DlqMessageRecord(
            DlqSourceType sourceType,
            String originalTopic,
            String originalKey,
            String originalMessage,
            String errorReason,
            String errorMessage,
            Long campaignId,
            Long userId,
            Long sequence,
            DlqReplayClassification replayClassification
    ) {
        this.sourceType = sourceType;
        this.originalTopic = originalTopic;
        this.originalKey = originalKey;
        this.originalMessage = originalMessage;
        this.errorReason = errorReason;
        this.errorMessage = errorMessage;
        this.campaignId = campaignId;
        this.userId = userId;
        this.sequence = sequence;
        this.replayClassification = replayClassification;
        this.processingStatus = DlqMessageProcessingStatus.PENDING;
        this.replayAttemptCount = 0;
    }

    public void incrementReplayAttempt() {
        this.replayAttemptCount++;
    }

    public void markReplayed(LocalDateTime replayedAt) {
        this.processingStatus = DlqMessageProcessingStatus.REPLAYED;
        this.lastReplayedAt = replayedAt;
        this.finalFailureReason = null;
    }

    public void markSkipped(String reason) {
        this.processingStatus = DlqMessageProcessingStatus.SKIPPED;
        this.finalFailureReason = reason;
    }

    public void markFinalFailed(String reason) {
        this.processingStatus = DlqMessageProcessingStatus.FINAL_FAILED;
        this.finalFailureReason = reason;
    }
}
