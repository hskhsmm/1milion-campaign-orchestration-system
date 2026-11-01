package io.eventdriven.campaign.application.service;

import io.eventdriven.campaign.application.event.DlqEventPayload;
import io.eventdriven.campaign.application.event.ParticipationEvent;
import io.eventdriven.campaign.config.KafkaConfig;
import io.eventdriven.campaign.domain.entity.DlqMessageRecord;
import io.eventdriven.campaign.domain.entity.DlqReplayClassification;
import io.eventdriven.campaign.domain.entity.DlqSourceType;
import io.eventdriven.campaign.domain.repository.DlqMessageRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class DlqMessageService {

    public static final String DLQ_TOPIC = KafkaConfig.TOPIC_NAME + ".dlq";

    private final DlqMessageRepository dlqMessageRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final tools.jackson.databind.json.JsonMapper jsonMapper;
    private final MeterRegistry meterRegistry;

    public DlqEventPayload createBridgePayload(
            Long fallbackCampaignId,
            String originalKey,
            String originalMessage,
            String errorReason,
            String errorMessage
    ) {
        ParticipationEvent event = tryParseParticipationEvent(originalMessage);
        DlqEventPayload payload = new DlqEventPayload();
        payload.setSourceType(DlqSourceType.BRIDGE);
        payload.setOriginalTopic(KafkaConfig.TOPIC_NAME);
        payload.setOriginalKey(originalKey);
        payload.setOriginalMessage(originalMessage);
        payload.setErrorReason(errorReason);
        payload.setErrorMessage(errorMessage);
        payload.setCampaignId(event != null && event.getCampaignId() != null ? event.getCampaignId() : fallbackCampaignId);
        payload.setUserId(event != null ? event.getUserId() : null);
        payload.setSequence(event != null ? event.getSequence() : null);
        payload.setReplayClassification(resolveBridgeClassification(event));
        payload.setOccurredAt(LocalDateTime.now());
        return payload;
    }

    public DlqEventPayload createConsumerPayload(
            String originalKey,
            String originalMessage,
            String errorReason,
            String errorMessage
    ) {
        ParticipationEvent event = tryParseParticipationEvent(originalMessage);
        DlqEventPayload payload = new DlqEventPayload();
        payload.setSourceType(DlqSourceType.CONSUMER);
        payload.setOriginalTopic(KafkaConfig.TOPIC_NAME);
        payload.setOriginalKey(originalKey);
        payload.setOriginalMessage(originalMessage);
        payload.setErrorReason(errorReason);
        payload.setErrorMessage(errorMessage);
        payload.setCampaignId(event != null ? event.getCampaignId() : null);
        payload.setUserId(event != null ? event.getUserId() : null);
        payload.setSequence(event != null ? event.getSequence() : null);
        payload.setReplayClassification(resolveConsumerClassification(errorReason, event));
        payload.setOccurredAt(LocalDateTime.now());
        return payload;
    }

    /**
     * dlq_message 저장이 커밋된 뒤에만 정상 반환한다. 저장 실패는 호출자에게 전달해 원본 ACK를 막는다.
     * 호출자 트랜잭션에 묶이면 커밋 시점이 늦어지므로 트랜잭션 밖에서 호출한다.
     * DLQ 토픽은 보조 기록이라 발행 실패는 로그와 지표만 남긴다.
     */
    public void publishAndStore(DlqEventPayload payload) {
        try {
            saveDlqMessage(payload);
        } catch (RuntimeException e) {
            counter("dlq.db.store.failed", "DLQ messages that failed to persist", payload).increment();
            throw e;
        }
        counter("dlq.db.stored", "DLQ messages persisted to dlq_message", payload).increment();
        publishDlqEvent(payload);
    }

    private DlqReplayClassification resolveBridgeClassification(ParticipationEvent event) {
        if (event == null || event.getCampaignId() == null || event.getUserId() == null || event.getSequence() == null) {
            return DlqReplayClassification.NON_REPLAYABLE;
        }
        return DlqReplayClassification.REPLAYABLE;
    }

    private DlqReplayClassification resolveConsumerClassification(String errorReason, ParticipationEvent event) {
        if ("JSON_PARSE_ERROR".equals(errorReason) || "INVALID_PAYLOAD".equals(errorReason)) {
            return DlqReplayClassification.NON_REPLAYABLE;
        }
        if (event == null || event.getCampaignId() == null || event.getUserId() == null || event.getSequence() == null) {
            return DlqReplayClassification.NON_REPLAYABLE;
        }
        return DlqReplayClassification.CONDITIONALLY_REPLAYABLE;
    }

    private ParticipationEvent tryParseParticipationEvent(String originalMessage) {
        try {
            return jsonMapper.readValue(originalMessage, ParticipationEvent.class);
        } catch (Exception e) {
            return null;
        }
    }

    // SimpleJpaRepository.save는 자체 트랜잭션으로 커밋한 뒤 반환한다.
    private void saveDlqMessage(DlqEventPayload payload) {
        dlqMessageRepository.save(new DlqMessageRecord(
                payload.getSourceType(),
                payload.getOriginalTopic(),
                payload.getOriginalKey(),
                payload.getOriginalMessage(),
                payload.getErrorReason(),
                payload.getErrorMessage(),
                payload.getCampaignId(),
                payload.getUserId(),
                payload.getSequence(),
                payload.getReplayClassification()
        ));
    }

    private void publishDlqEvent(DlqEventPayload payload) {
        try {
            String message = jsonMapper.writeValueAsString(payload);
            kafkaTemplate.send(DLQ_TOPIC, payload.getOriginalKey(), message)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            recordPublishFailure(payload, ex);
                        }
                    });
        } catch (Exception e) {
            recordPublishFailure(payload, e);
        }
    }

    private void recordPublishFailure(DlqEventPayload payload, Throwable cause) {
        counter("dlq.topic.publish.failed", "DLQ topic publishes that failed", payload).increment();
        log.error("Failed to publish DLQ event. sourceType={}, reason={}",
                payload.getSourceType(), payload.getErrorReason(), cause);
    }

    private Counter counter(String name, String description, DlqEventPayload payload) {
        return Counter.builder(name)
                .description(description)
                .tag("sourceType", String.valueOf(payload.getSourceType()))
                .register(meterRegistry);
    }
}
