ALTER TABLE participation_history
    ADD COLUMN sequence BIGINT NULL;

ALTER TABLE participation_history
    MODIFY COLUMN status ENUM('FAIL', 'SUCCESS', 'PENDING') NOT NULL;
