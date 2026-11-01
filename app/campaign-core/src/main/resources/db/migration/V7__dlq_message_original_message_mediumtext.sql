-- Kafka 기본 최대 메시지(약 1MB) 원문을 자르지 않고 보관한다. TEXT(64KB)를 넘으면 저장이 실패해 원본 ACK가 막힌다.
ALTER TABLE dlq_message
    MODIFY COLUMN original_message MEDIUMTEXT NOT NULL;
