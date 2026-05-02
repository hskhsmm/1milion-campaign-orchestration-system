package io.eventdriven.campaign.domain.entity;

public enum DlqReplayAction {
    REPLAY,
    SKIP,
    FINAL_FAIL
}
