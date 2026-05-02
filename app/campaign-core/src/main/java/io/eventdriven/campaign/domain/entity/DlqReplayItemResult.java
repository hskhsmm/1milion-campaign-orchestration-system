package io.eventdriven.campaign.domain.entity;

public enum DlqReplayItemResult {
    SUCCESS,
    SKIPPED,
    FINAL_FAILED,
    FAILED,
    DRY_RUN
}
