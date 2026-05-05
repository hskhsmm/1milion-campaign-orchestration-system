package io.eventdriven.campaign.application.consumer;

import io.eventdriven.campaign.application.event.DlqEventPayload;
import io.eventdriven.campaign.application.event.ParticipationEvent;
import io.eventdriven.campaign.application.service.DlqMessageService;
import io.eventdriven.campaign.application.service.SlackNotificationService;
import io.eventdriven.campaign.domain.repository.ParticipationHistoryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ParticipationEventConsumer {

    private final JsonMapper jsonMapper;
    private final ParticipationHistoryRepository participationHistoryRepository;
    private final JdbcTemplate jdbcTemplate;
    private final SlackNotificationService slackNotificationService;
    private final DlqMessageService dlqMessageService;
    private final MeterRegistry meterRegistry;

    @KafkaListener(
            topics = "campaign-participation-topic",
            groupId = "campaign-participation-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeParticipationEvent(List<ConsumerRecord<String, String>> records, Acknowledgment acknowledgment) {
        List<ParticipationEvent> events = parseRecords(records);
        if (events.isEmpty()) {
            acknowledgment.acknowledge();
            return;
        }

        LocalDateTime batchStart = LocalDateTime.now();
        List<ParticipationEvent> successEvents = new ArrayList<>();
        boolean hasTransientFailure = false;

        try {
            List<Object[]> batchArgs = new ArrayList<>(events.size());
            for (ParticipationEvent event : events) {
                batchArgs.add(new Object[]{event.getCampaignId(), event.getUserId(), event.getSequence()});
            }
            jdbcTemplate.batchUpdate(
                    "INSERT IGNORE INTO participation_history "
                            + "(campaign_id, user_id, sequence, status, created_at) "
                            + "VALUES (?, ?, ?, 'SUCCESS', NOW())",
                    batchArgs
            );
            successEvents.addAll(events);
        } catch (Exception batchException) {
            log.warn("Batch insert failed. Falling back to row-by-row insert. count={}", events.size(), batchException);
            for (ParticipationEvent event : events) {
                try {
                    participationHistoryRepository.insertSuccess(
                            event.getCampaignId(),
                            event.getUserId(),
                            event.getSequence()
                    );
                    successEvents.add(event);
                } catch (DataIntegrityViolationException e) {
                    log.warn("Duplicate success ignored. campaignId={}, userId={}, sequence={}",
                            event.getCampaignId(), event.getUserId(), event.getSequence());
                    successEvents.add(event);
                } catch (Exception e) {
                    log.error("Insert failed (transient). campaignId={}, userId={}, sequence={}",
                            event.getCampaignId(), event.getUserId(), event.getSequence(), e);
                    hasTransientFailure = true;
                    sendToDlqWithSlack(
                            String.valueOf(event.getUserId()),
                            serializeEvent(event),
                            "INSERT_FAILED",
                            e
                    );
                }
            }
        }

        // ③ 지연시간 측정 (API LPUSH ~ Consumer INSERT 완료)
        long latencyMs = Duration.between(batchStart, LocalDateTime.now()).toMillis();
        Timer.builder("consumer.pending_to_success.latency")
                .description("Time from consumer batch start to DB success insert")
                .register(meterRegistry)
                .record(latencyMs, TimeUnit.MILLISECONDS);

        log.info("Consumer batch processed. polled={}, parsed={}, success={}, transientFailure={}, latencyMs={}",
                records.size(), events.size(), successEvents.size(), hasTransientFailure, latencyMs);

        // ⑤ Kafka 오프셋 커밋 — transient failure(RDS 다운 등) 시 ack 보류 → Kafka 자동 재전달
        if (!hasTransientFailure) {
            acknowledgment.acknowledge();
        } else {
            log.warn("Skipping ack due to transient DB failure. Kafka will redeliver after recovery. count={}",
                    events.size() - successEvents.size());
        }
    }

    private List<ParticipationEvent> parseRecords(List<ConsumerRecord<String, String>> records) {
        List<ParticipationEvent> events = new ArrayList<>();
        for (ConsumerRecord<String, String> record : records) {
            try {
                ParticipationEvent event = jsonMapper.readValue(record.value(), ParticipationEvent.class);
                if (event.getSequence() == null) {
                    log.warn("Missing sequence. Sending to DLQ. payload={}", record.value());
                    sendToDlqWithSlack(record.key(), record.value(), "MISSING_SEQUENCE", null);
                    continue;
                }
                events.add(event);
            } catch (Exception e) {
                log.error("Failed to parse consumer payload. payload={}", record.value(), e);
                sendToDlqWithSlack(record.key(), record.value(), "JSON_PARSE_ERROR", e);
            }
        }
        return events;
    }

    private void sendToDlqWithSlack(
            String originalKey,
            String originalMessage,
            String errorReason,
            Exception exception
    ) {
        try {
            DlqEventPayload payload = dlqMessageService.createConsumerPayload(
                    originalKey,
                    originalMessage,
                    errorReason,
                    exception != null ? exception.getMessage() : null
            );
            dlqMessageService.publishAndStore(payload);
        } catch (Exception e) {
            log.error("Failed to publish consumer DLQ payload. reason={}", errorReason, e);
        }

        slackNotificationService.sendDlqAlert("Consumer " + errorReason, originalMessage);
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
