package io.eventdriven.campaign.domain.entity;

public enum DlqReplayClassification {
    REPLAYABLE,
    CONDITIONALLY_REPLAYABLE,
    NON_REPLAYABLE
}
