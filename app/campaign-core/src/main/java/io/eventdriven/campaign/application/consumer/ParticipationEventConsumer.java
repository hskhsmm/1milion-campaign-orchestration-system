package io.eventdriven.campaign.application.consumer;

import io.eventdriven.campaign.application.event.DlqEventPayload;
import io.eventdriven.campaign.application.event.ParticipationEvent;
import io.eventdriven.campaign.application.service.DlqMessageService;
import io.eventdriven.campaign.application.service.SlackNotificationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 배치의 모든 메시지가 참여 이력(정상) 또는 dlq_message(메시지 자체의 문제)에 저장된 뒤에만 ACK한다.
 * 어느 쪽에도 저장하지 못하면 nack으로 배치 처음부터 다시 받는다. ACK 생략만으로는 재전달되지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParticipationEventConsumer {

    private final JsonMapper jsonMapper;
    private final JdbcTemplate jdbcTemplate;
    private final SlackNotificationService slackNotificationService;
    private final DlqMessageService dlqMessageService;
    private final MeterRegistry meterRegistry;

    @Value("${kafka.consumer.retry-backoff-ms:1000}")
    private long retryBackoffMs;

    // 중복만 무시한다. INSERT IGNORE는 FK·NOT NULL 위반까지 경고로 바꿔 성공처럼 보이게 한다.
    private static final String INSERT_SUCCESS_SQL =
            "INSERT INTO participation_history "
                    + "(campaign_id, user_id, sequence, status, created_at, updated_at) "
                    + "VALUES (?, ?, ?, 'SUCCESS', NOW(), NOW()) "
                    + "ON DUPLICATE KEY UPDATE id = id";

    private static final int RETRY_REQUIRED = -1;

    private static final String METRIC_CONSUMER_POLLED = "consumer.kafka.records.polled";
    private static final String METRIC_CONSUMER_PARSED = "consumer.events.parsed";
    private static final String METRIC_DB_COMMITTED = "consumer.db.committed";
    private static final String METRIC_DB_COMMIT_BATCH_SIZE = "consumer.db.commit.batch.size";
    private static final String METRIC_DB_TRANSIENT_FAILURE = "consumer.db.transient.failures";
    private static final String METRIC_BATCH_RETRIED = "consumer.batch.retried";

    @KafkaListener(
            topics = "campaign-participation-topic",
            groupId = "campaign-participation-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeParticipationEvent(List<ConsumerRecord<String, String>> records, Acknowledgment acknowledgment) {
        long batchStartNanos = System.nanoTime();
        List<ParticipationEvent> events = new ArrayList<>();
        List<DlqEventPayload> dlqPayloads = new ArrayList<>();
        parseRecords(records, events, dlqPayloads);

        int storedCount = 0;
        if (!events.isEmpty()) {
            storedCount = storeEvents(events, dlqPayloads);
            long dbProcessingNanos = System.nanoTime() - batchStartNanos;
            recordDbProcessingLatency(dbProcessingNanos);

            if (storedCount == RETRY_REQUIRED) {
                recordBackendThroughput(records.size(), events.size(), 0, events.size());
                retryBatch(acknowledgment, "db_failure", records.size());
                return;
            }

            log.info("Consumer batch stored. polled={}, parsed={}, stored={}, dlq={}, latencyMs={}",
                    records.size(), events.size(), storedCount, dlqPayloads.size(),
                    TimeUnit.NANOSECONDS.toMillis(dbProcessingNanos));
        }
        recordBackendThroughput(records.size(), events.size(), storedCount, 0);

        if (!storeDlqPayloads(dlqPayloads)) {
            retryBatch(acknowledgment, "dlq_store_failure", records.size());
            return;
        }

        acknowledgment.acknowledge();
    }

    /**
     * @return 참여 이력에 저장된(이미 있던 중복 포함) 이벤트 수. 메시지 탓이 아닌 DB 실패면 RETRY_REQUIRED.
     */
    private int storeEvents(List<ParticipationEvent> events, List<DlqEventPayload> dlqPayloads) {
        try {
            List<Object[]> batchArgs = new ArrayList<>(events.size());
            for (ParticipationEvent event : events) {
                batchArgs.add(new Object[]{event.getCampaignId(), event.getUserId(), event.getSequence()});
            }
            jdbcTemplate.batchUpdate(INSERT_SUCCESS_SQL, batchArgs);
            return events.size();
        } catch (DataIntegrityViolationException batchException) {
            log.warn("Batch insert hit a data integrity violation. Isolating rows. count={}",
                    events.size(), batchException);
        } catch (Exception batchException) {
            // 연결 실패·타임아웃뿐 아니라 권한·읽기 전용·디스크 부족도 메시지 탓이 아니므로 DLQ로 보내지 않는다.
            log.error("Batch insert failed without a message-level cause. Retrying batch. count={}",
                    events.size(), batchException);
            return RETRY_REQUIRED;
        }

        int storedCount = 0;
        for (ParticipationEvent event : events) {
            try {
                jdbcTemplate.update(INSERT_SUCCESS_SQL, event.getCampaignId(), event.getUserId(), event.getSequence());
                storedCount++;
            } catch (DataIntegrityViolationException e) {
                log.warn("Insert rejected by data integrity. Sending to DLQ. campaignId={}, userId={}, sequence={}",
                        event.getCampaignId(), event.getUserId(), event.getSequence(), e);
                dlqPayloads.add(createDlqPayload(String.valueOf(event.getUserId()), serializeEvent(event),
                        "INSERT_FAILED", e));
            } catch (Exception e) {
                log.error("Row insert failed without a message-level cause. Retrying batch. campaignId={}, userId={}, sequence={}",
                        event.getCampaignId(), event.getUserId(), event.getSequence(), e);
                return RETRY_REQUIRED;
            }
        }
        return storedCount;
    }

    private boolean storeDlqPayloads(List<DlqEventPayload> dlqPayloads) {
        for (DlqEventPayload payload : dlqPayloads) {
            try {
                dlqMessageService.publishAndStore(payload);
            } catch (Exception e) {
                log.error("Failed to persist consumer DLQ payload. Retrying batch. reason={}",
                        payload.getErrorReason(), e);
                return false;
            }
            slackNotificationService.sendDlqAlert("Consumer " + payload.getErrorReason(), payload.getOriginalMessage());
        }
        return true;
    }

    // nack(0)은 이 배치의 어떤 offset도 커밋하지 않고 배치 처음부터 다시 받게 한다.
    // 이미 저장한 행은 중복 무시로 걸러지지만, 이미 저장한 DLQ 기록은 다시 쌓일 수 있다.
    private void retryBatch(Acknowledgment acknowledgment, String reason, int recordCount) {
        incrementCounter(METRIC_BATCH_RETRIED, "Consumer batches nacked for redelivery", 1, "reason", reason);
        log.warn("Nacking consumer batch for redelivery. reason={}, records={}, backoffMs={}",
                reason, recordCount, retryBackoffMs);
        acknowledgment.nack(0, Duration.ofMillis(retryBackoffMs));
    }

    private void recordBackendThroughput(
            int polledCount,
            int parsedCount,
            int storedCount,
            int transientFailureCount
    ) {
        incrementCounter(METRIC_CONSUMER_POLLED, "Kafka records polled by participation consumer", polledCount);
        incrementCounter(METRIC_CONSUMER_PARSED, "Participation events parsed by consumer", parsedCount);
        // 신규 INSERT 수가 아니다. 이미 있던 중복과 재전달된 배치의 재처리도 포함한다.
        incrementCounter(METRIC_DB_COMMITTED,
                "Participation events stored or already present in DB, including redelivered duplicates", storedCount);

        if (parsedCount > 0) {
            DistributionSummary.builder(METRIC_DB_COMMIT_BATCH_SIZE)
                    .description("DB commit batch size per consumer poll")
                    .register(meterRegistry)
                    .record(storedCount);
        }

        incrementCounter(
                METRIC_DB_TRANSIENT_FAILURE,
                "Participation events in batches retried after a DB failure",
                transientFailureCount
        );
    }

    private void recordDbProcessingLatency(long latencyNanos) {
        Timer.builder("consumer.pending_to_success.latency")
                .description("Time from Kafka batch receipt to DB processing completion")
                .register(meterRegistry)
                .record(latencyNanos, TimeUnit.NANOSECONDS);
    }

    private void incrementCounter(String name, String description, double amount, String... tags) {
        if (amount <= 0) {
            return;
        }
        Counter.builder(name)
                .description(description)
                .tags(tags)
                .register(meterRegistry)
                .increment(amount);
    }

    private void parseRecords(
            List<ConsumerRecord<String, String>> records,
            List<ParticipationEvent> events,
            List<DlqEventPayload> dlqPayloads
    ) {
        for (ConsumerRecord<String, String> record : records) {
            // 실제 null value(tombstone)는 JSON 문자열 "null"(INVALID_PAYLOAD)과 사유로 구별해 보관한다.
            if (record.value() == null) {
                log.warn("Null participation record value. Sending to DLQ. key={}", record.key());
                dlqPayloads.add(createDlqPayload(record.key(), null, "NULL_VALUE", null));
                continue;
            }
            ParticipationEvent event;
            try {
                event = jsonMapper.readValue(record.value(), ParticipationEvent.class);
            } catch (Exception e) {
                log.error("Failed to parse consumer payload. Sending to DLQ. payload={}", record.value(), e);
                dlqPayloads.add(createDlqPayload(record.key(), record.value(), "JSON_PARSE_ERROR", e));
                continue;
            }
            if (!hasRequiredFields(event)) {
                log.warn("Invalid participation payload. Sending to DLQ. payload={}", record.value());
                dlqPayloads.add(createDlqPayload(record.key(), record.value(), "INVALID_PAYLOAD", null));
                continue;
            }
            events.add(event);
        }
    }

    private boolean hasRequiredFields(ParticipationEvent event) {
        return event != null
                && isPositive(event.getCampaignId())
                && isPositive(event.getUserId())
                && isPositive(event.getSequence());
    }

    private boolean isPositive(Long value) {
        return value != null && value > 0;
    }

    private DlqEventPayload createDlqPayload(
            String originalKey,
            String originalMessage,
            String errorReason,
            Exception exception
    ) {
        return dlqMessageService.createConsumerPayload(
                originalKey,
                originalMessage,
                errorReason,
                exception != null ? exception.getMessage() : null
        );
    }

    private String serializeEvent(ParticipationEvent event) {
        try {
            return jsonMapper.writeValueAsString(event);
        } catch (Exception e) {
            return "{\"campaignId\":" + event.getCampaignId()
                    + ",\"userId\":" + event.getUserId()
                    + ",\"sequence\":" + event.getSequence() + "}";
        }
    }
}
