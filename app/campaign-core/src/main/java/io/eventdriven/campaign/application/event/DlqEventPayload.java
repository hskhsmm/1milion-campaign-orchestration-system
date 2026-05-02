package io.eventdriven.campaign.application.event;

import io.eventdriven.campaign.domain.entity.DlqReplayClassification;
import io.eventdriven.campaign.domain.entity.DlqSourceType;

import java.time.LocalDateTime;

public class DlqEventPayload {
    private DlqSourceType sourceType;
    private String originalTopic;
    private String originalKey;
    private String originalMessage;
    private String errorReason;
    private String errorMessage;
    private Long campaignId;
    private Long userId;
    private Long sequence;
    private DlqReplayClassification replayClassification;
    private LocalDateTime occurredAt;

    public DlqSourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(DlqSourceType sourceType) {
        this.sourceType = sourceType;
    }

    public String getOriginalTopic() {
        return originalTopic;
    }

    public void setOriginalTopic(String originalTopic) {
        this.originalTopic = originalTopic;
    }

    public String getOriginalKey() {
        return originalKey;
    }

    public void setOriginalKey(String originalKey) {
        this.originalKey = originalKey;
    }

    public String getOriginalMessage() {
        return originalMessage;
    }

    public void setOriginalMessage(String originalMessage) {
        this.originalMessage = originalMessage;
    }

    public String getErrorReason() {
        return errorReason;
    }

    public void setErrorReason(String errorReason) {
        this.errorReason = errorReason;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Long getCampaignId() {
        return campaignId;
    }

    public void setCampaignId(Long campaignId) {
        this.campaignId = campaignId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getSequence() {
        return sequence;
    }

    public void setSequence(Long sequence) {
        this.sequence = sequence;
    }

    public DlqReplayClassification getReplayClassification() {
        return replayClassification;
    }

    public void setReplayClassification(DlqReplayClassification replayClassification) {
        this.replayClassification = replayClassification;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(LocalDateTime occurredAt) {
        this.occurredAt = occurredAt;
    }
}
