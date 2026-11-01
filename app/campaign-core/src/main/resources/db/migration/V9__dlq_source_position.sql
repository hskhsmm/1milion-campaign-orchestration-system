ALTER TABLE dlq_message
 ADD COLUMN original_partition INT NULL,
 ADD COLUMN original_offset BIGINT NULL,
 ADD CONSTRAINT uk_dlq_source UNIQUE(original_topic, original_partition, original_offset);
