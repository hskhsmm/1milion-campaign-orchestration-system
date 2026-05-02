package io.eventdriven.campaign.domain.entity;

public enum ConsistencyRecoveryAction {
    NONE,
    REPORT_ONLY,
    RESTORE_REDIS_STATE,
    ACTIVATE_CAMPAIGN,
    CLEANUP_REDIS_STATE
}
