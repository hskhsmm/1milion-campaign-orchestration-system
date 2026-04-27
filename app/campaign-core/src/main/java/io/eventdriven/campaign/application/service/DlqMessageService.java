package io.eventdriven.campaign.application.service;

import io.eventdriven.campaign.application.event.DlqEventPayload;
import io.eventdriven.campaign.application.event.ParticipationEvent;
import io.eventdriven.campaign.config.KafkaConfig;
import io.eventdriven.campaign.domain.entity.DlqMessageRecord;
import io.eventdriven.campaign.domain.entity.DlqReplayClassification;
import io.eventdriven.campaign.domain.entity.DlqSourceType;
import io.eventdriven.campaign.domain.repository.DlqMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class DlqMessageService {

    public static final String DLQ_TOPIC = KafkaConfig.TOPIC_NAME + ".dlq";

    private final DlqMessageRepository dlqMessageRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final tools.jackson.databind.json.JsonMapper jsonMapper;

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

    @Transactional
    public void publishAndStore(DlqEventPayload payload) {
        saveDlqMessage(payload);
        publishDlqEvent(payload);
    }

    private DlqReplayClassification resolveBridgeClassification(ParticipationEvent event) {
        if (event == null || event.getCampaignId() == null || event.getUserId() == null || event.getSequence() == null) {
            return DlqReplayClassification.NON_REPLAYABLE;
        }
        return DlqReplayClassification.REPLAYABLE;
    }

    private DlqReplayClassification resolveConsumerClassification(String errorReason, ParticipationEvent event) {
        if ("JSON_PARSE_ERROR".equals(errorReason) || "MISSING_SEQUENCE".equals(errorReason)) {
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

    private void saveDlqMessage(DlqEventPayload payload) {
        try {
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
        } catch (Exception e) {
            log.error("Failed to persist DLQ message. sourceType={}, reason={}",
                    payload.getSourceType(), payload.getErrorReason(), e);
        }
    }

    private void publishDlqEvent(DlqEventPayload payload) {
        try {
            String message = jsonMapper.writeValueAsString(payload);
            kafkaTemplate.send(DLQ_TOPIC, payload.getOriginalKey(), message);
        } catch (Exception e) {
            log.error("Failed to publish DLQ event. sourceType={}, reason={}",
                    payload.getSourceType(), payload.getErrorReason(), e);
        }
    }
}
