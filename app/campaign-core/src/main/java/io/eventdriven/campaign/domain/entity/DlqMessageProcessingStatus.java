package io.eventdriven.campaign.domain.entity;

public enum DlqMessageProcessingStatus {
    PENDING,
    REPLAYED,
    SKIPPED,
    FINAL_FAILED
}
