ALTER TABLE dlq_replay_execution
    ADD COLUMN last_message_id BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN processed_count INT NOT NULL DEFAULT 0;

-- Preserve progress when resuming an older execution with committed item history.
UPDATE dlq_replay_execution e
JOIN (SELECT execution_id, MAX(dlq_message_id) AS last_id, COUNT(*) AS item_count
      FROM dlq_replay_execution_item GROUP BY execution_id) i ON i.execution_id=e.id
SET e.last_message_id=i.last_id, e.processed_count=i.item_count;
