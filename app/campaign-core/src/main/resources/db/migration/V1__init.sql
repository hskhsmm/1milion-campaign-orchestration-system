CREATE TABLE campaign (
    id          BIGINT          NOT NULL AUTO_INCREMENT,
    name        VARCHAR(255)    NOT NULL,
    total_stock BIGINT          NOT NULL,
    current_stock BIGINT        NOT NULL,
    status      ENUM('OPEN', 'CLOSED') NOT NULL,
    created_at  DATETIME(6)     NOT NULL,
    updated_at  DATETIME(6)     NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE participation_history (
    id                          BIGINT          NOT NULL AUTO_INCREMENT,
    campaign_id                 BIGINT          NOT NULL,
    user_id                     BIGINT          NOT NULL,
    status                      ENUM('SUCCESS', 'FAIL', 'PENDING') NOT NULL,
    kafka_offset                BIGINT,
    kafka_partition             INT,
    kafka_timestamp             BIGINT,
    processing_started_at_nanos BIGINT,
    processing_sequence         BIGINT,
    created_at                  DATETIME(6)     NOT NULL,
    updated_at                  DATETIME(6)     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_participation_campaign FOREIGN KEY (campaign_id) REFERENCES campaign (id)
);

CREATE TABLE campaign_stats (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    campaign_id   BIGINT      NOT NULL,
    success_count BIGINT      NOT NULL,
    fail_count    BIGINT      NOT NULL,
    stats_date    DATE        NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_campaign_stats_date UNIQUE (campaign_id, stats_date),
    CONSTRAINT fk_stats_campaign FOREIGN KEY (campaign_id) REFERENCES campaign (id)
);
