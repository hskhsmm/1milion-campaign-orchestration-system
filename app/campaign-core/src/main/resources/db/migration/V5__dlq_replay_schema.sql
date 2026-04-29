CREATE TABLE dlq_message (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    source_type             VARCHAR(32)  NOT NULL,
    original_topic          VARCHAR(255) NOT NULL,
    original_key            VARCHAR(255),
    original_message        TEXT         NOT NULL,
    error_reason            VARCHAR(100) NOT NULL,
    error_message           VARCHAR(1000),
    campaign_id             BIGINT,
    user_id                 BIGINT,
    sequence_no             BIGINT,
    replay_classification   VARCHAR(32)  NOT NULL,
    processing_status       VARCHAR(32)  NOT NULL,
    replay_attempt_count    INT          NOT NULL DEFAULT 0,
    final_failure_reason    VARCHAR(255),
    last_replayed_at        DATETIME(6),
    created_at              DATETIME(6)  NOT NULL,
    updated_at              DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_dlq_message_status_id
    ON dlq_message (processing_status, id);

CREATE INDEX idx_dlq_message_campaign_status
    ON dlq_message (campaign_id, processing_status);

CREATE INDEX idx_dlq_message_reason_status
    ON dlq_message (error_reason, processing_status);

CREATE TABLE dlq_replay_execution (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    requested_by         VARCHAR(100),
    dry_run              BIT(1)       NOT NULL,
    filter_campaign_id   BIGINT,
    filter_reason        VARCHAR(100),
    filter_from_time     DATETIME(6),
    filter_to_time       DATETIME(6),
    max_items            INT          NOT NULL,
    target_count         BIGINT       NOT NULL DEFAULT 0,
    replayed_count       BIGINT       NOT NULL DEFAULT 0,
    skipped_count        BIGINT       NOT NULL DEFAULT 0,
    final_failed_count   BIGINT       NOT NULL DEFAULT 0,
    publish_failed_count BIGINT       NOT NULL DEFAULT 0,
    status               VARCHAR(32)  NOT NULL,
    started_at           DATETIME(6),
    ended_at             DATETIME(6),
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE dlq_replay_execution_item (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    execution_id   BIGINT        NOT NULL,
    dlq_message_id BIGINT        NOT NULL,
    original_topic VARCHAR(255)  NOT NULL,
    original_key   VARCHAR(255),
    reason         VARCHAR(100)  NOT NULL,
    action         VARCHAR(32)   NOT NULL,
    result         VARCHAR(32)   NOT NULL,
    detail_message VARCHAR(1000),
    processed_at   DATETIME(6)   NOT NULL,
    created_at     DATETIME(6)   NOT NULL,
    updated_at     DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_dlq_replay_execution_item_execution
        FOREIGN KEY (execution_id) REFERENCES dlq_replay_execution (id),
    CONSTRAINT fk_dlq_replay_execution_item_message
        FOREIGN KEY (dlq_message_id) REFERENCES dlq_message (id),
    CONSTRAINT uk_dlq_replay_execution_message
        UNIQUE (execution_id, dlq_message_id)
);

CREATE INDEX idx_dlq_replay_execution_item_execution
    ON dlq_replay_execution_item (execution_id);
