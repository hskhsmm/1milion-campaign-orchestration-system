package io.eventdriven.campaign.domain.entity;

public enum DlqReplayExecutionStatus {
    REQUESTED,
    RUNNING,
    COMPLETED,
    FAILED
}
