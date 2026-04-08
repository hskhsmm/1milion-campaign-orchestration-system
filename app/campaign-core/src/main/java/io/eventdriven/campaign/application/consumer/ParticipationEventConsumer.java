package io.eventdriven.campaign.application.consumer;

import io.eventdriven.campaign.application.event.ParticipationEvent;
import io.eventdriven.campaign.domain.entity.ParticipationHistory;
import io.eventdriven.campaign.domain.entity.ParticipationStatus;
import io.eventdriven.campaign.domain.repository.ParticipationHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 선착순 참여 이벤트 Consumer (v2)
 *
 * v2 역할: API 단계에서 이미 PENDING으로 선점된 자리를 SUCCESS로 마무리하는 것.
 * - Redis DECR: API 진입 단계(A파트)에서 처리 완료
 * - DB INSERT: A파트에서 PENDING으로 선점 완료
 * - Consumer는 historyId로 PENDING 조회 후 배치 UPDATE만 담당
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParticipationEventConsumer {

    private final JsonMapper jsonMapper;
    private final ParticipationHistoryRepository participationHistoryRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    private static final String DLQ_TOPIC = "campaign-participation-topic.dlq";

    @KafkaListener(
            topics = "campaign-participation-topic",
            groupId = "campaign-participation-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeParticipationEvent(List<ConsumerRecord<String, String>> records, Acknowledgment acknowledgment) {
        log.info("Kafka 배치 수신. {}건", records.size());

        // ① 메시지 파싱
        // historyId 없는 메시지(v1 형식, 잘못된 메시지)는 Poison Pill로 즉시 DLQ 전송
        List<ParticipationEvent> events = parseRecords(records);

        if (events.isEmpty()) {
            acknowledgment.acknowledge();
            return;
        }

        // ② DB 일괄 조회
        // findAllById → WHERE id IN (...) 쿼리 1번으로 N개 PENDING 행 조회 (N번 왕복 아님)
        List<Long> historyIds = events.stream()
                .map(ParticipationEvent::getHistoryId)
                .collect(Collectors.toList());

        Map<Long, ParticipationHistory> historyMap = participationHistoryRepository.findAllById(historyIds)
                .stream()
                .collect(Collectors.toMap(ParticipationHistory::getId, h -> h));

        // ③ PENDING 상태 검증 및 분류
        // DB에 없거나 이미 SUCCESS/FAIL이면 중복 처리 방지를 위해 DLQ로 격리
        List<Long> validHistoryIds = new ArrayList<>();

        for (ParticipationEvent event : events) {
            ParticipationHistory history = historyMap.get(event.getHistoryId());

            if (history == null) {
                log.warn("DB에 존재하지 않는 historyId={} - DLQ 전송", event.getHistoryId());
                sendToDlq(event.getHistoryId().toString(), "HISTORY_NOT_FOUND", null);
                continue;
            }
            if (history.getStatus() != ParticipationStatus.PENDING) {
                log.warn("PENDING이 아닌 상태 historyId={}, status={} - DLQ 전송 (중복 처리 방지)",
                        event.getHistoryId(), history.getStatus());
                sendToDlq(event.getHistoryId().toString(), "NOT_PENDING_STATUS", null);
                continue;
            }

            validHistoryIds.add(event.getHistoryId());
        }

        // ④ 배치 UPDATE: PENDING → SUCCESS
        // WHERE id IN (validHistoryIds) 쿼리 1번으로 전체 처리
        if (!validHistoryIds.isEmpty()) {
            try {
                int updated = participationHistoryRepository.bulkUpdateSuccess(validHistoryIds);
                log.info("SUCCESS 업데이트 완료. {}건", updated);
            } catch (Exception e) {
                log.error("배치 UPDATE 실패. {}건 DLQ 전송.", validHistoryIds.size(), e);
                sendBatchToDlq(validHistoryIds, "BULK_UPDATE_ERROR", e);
            }
        }

        // ⑤ Kafka 오프셋 커밋
        // 오류가 발생한 메시지는 DLQ로 격리했으므로 무조건 커밋 (무한 재처리 방지)
        acknowledgment.acknowledge();
    }

    /**
     * 레코드 파싱 및 Poison Pill 필터링
     * historyId가 없는 메시지는 v1 형식이거나 잘못된 메시지이므로 DLQ로 격리
     */
    private List<ParticipationEvent> parseRecords(List<ConsumerRecord<String, String>> records) {
        List<ParticipationEvent> events = new ArrayList<>();
        for (ConsumerRecord<String, String> record : records) {
            try {
                ParticipationEvent event = jsonMapper.readValue(record.value(), ParticipationEvent.class);
                if (event.getHistoryId() == null) {
                    log.warn("historyId 없는 메시지 (Poison Pill) - DLQ 전송: {}", record.value());
                    sendToDlq(record.value(), "MISSING_HISTORY_ID", null);
                    continue;
                }
                events.add(event);
            } catch (Exception e) {
                log.error("JSON 파싱 실패 - DLQ 전송: {}", record.value(), e);
                sendToDlq(record.value(), "JSON_PARSE_ERROR", e);
            }
        }
        return events;
    }

    /**
     * DLQ 전송 (단일 메시지)
     * DLQ 전송 자체가 실패하면 로그로만 기록 (무한 루프 방지)
     */
    private void sendToDlq(String originalMessage, String errorReason, Exception exception) {
        try {
            Map<String, Object> dlqMessage = new HashMap<>();
            dlqMessage.put("originalMessage", originalMessage);
            dlqMessage.put("errorReason", errorReason);
            dlqMessage.put("errorMessage", exception != null ? exception.getMessage() : null);
            dlqMessage.put("timestamp", LocalDateTime.now().toString());

            kafkaTemplate.send(DLQ_TOPIC, jsonMapper.writeValueAsString(dlqMessage));
            log.info("DLQ 전송 완료 - 사유: {}", errorReason);
        } catch (Exception e) {
            log.error("CRITICAL: DLQ 전송 실패! 원본: {}", originalMessage, e);
        }
    }

    /**
     * DLQ 전송 (배치 UPDATE 실패 시)
     * historyId 목록을 통째로 DLQ에 기록
     */
    private void sendBatchToDlq(List<Long> historyIds, String errorReason, Exception exception) {
        try {
            Map<String, Object> dlqMessage = new HashMap<>();
            dlqMessage.put("historyIds", historyIds);
            dlqMessage.put("errorReason", errorReason);
            dlqMessage.put("errorMessage", exception != null ? exception.getMessage() : null);
            dlqMessage.put("timestamp", LocalDateTime.now().toString());

            kafkaTemplate.send(DLQ_TOPIC, jsonMapper.writeValueAsString(dlqMessage));
            log.info("배치 DLQ 전송 완료 - 사유: {}, {}건", errorReason, historyIds.size());
        } catch (Exception e) {
            log.error("CRITICAL: 배치 DLQ 전송 실패! historyIds: {}", historyIds, e);
        }
    }
}
