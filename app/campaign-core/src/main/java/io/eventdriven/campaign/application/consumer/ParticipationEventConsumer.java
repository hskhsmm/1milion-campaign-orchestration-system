package io.eventdriven.campaign.application.consumer;

import io.eventdriven.campaign.application.event.ParticipationEvent;
import io.eventdriven.campaign.application.service.SlackNotificationService;
import io.eventdriven.campaign.domain.entity.ParticipationHistory;
import io.eventdriven.campaign.domain.entity.ParticipationStatus;
import io.eventdriven.campaign.domain.repository.ParticipationHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
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
 *
 * 트랜잭션 범위: DB만 (bulkUpdateSuccess @Transactional)
 * Redis 캐시는 DB 커밋 후 작성 (트랜잭션 밖)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParticipationEventConsumer {

    private final JsonMapper jsonMapper;
    private final ParticipationHistoryRepository participationHistoryRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final RedisTemplate<String, String> redisTemplate;
    private final SlackNotificationService slackNotificationService;

    private static final String DLQ_TOPIC = "campaign-participation-topic.dlq";
    private static final String RESULT_CACHE_PREFIX = "participation:result:";
    private static final Duration RESULT_CACHE_TTL = Duration.ofSeconds(300);

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
        // validEvents: userId/campaignId 포함 → 캐시 키 생성에 필요
        List<ParticipationEvent> validEvents = new ArrayList<>();

        for (ParticipationEvent event : events) {
            ParticipationHistory history = historyMap.get(event.getHistoryId());

            if (history == null) {
                log.warn("DB에 존재하지 않는 historyId={} - DLQ 전송", event.getHistoryId());
                sendToDlqWithSlack(event.getHistoryId().toString(), "HISTORY_NOT_FOUND", null);
                continue;
            }
            if (history.getStatus() != ParticipationStatus.PENDING) {
                log.warn("PENDING이 아닌 상태 historyId={}, status={} - DLQ 전송 (중복 처리 방지)",
                        event.getHistoryId(), history.getStatus());
                sendToDlqWithSlack(event.getHistoryId().toString(), "NOT_PENDING_STATUS", null);
                continue;
            }

            validEvents.add(event);
        }

        // ④ 배치 UPDATE: PENDING → SUCCESS (DB 트랜잭션)
        // 성공 → Redis 결과 캐시 적재 (DB 커밋 후, 트랜잭션 밖)
        // 실패 → 개별 처리로 전환 (Poison Pill 격리 + ack 강행)
        if (!validEvents.isEmpty()) {
            List<Long> validHistoryIds = validEvents.stream()
                    .map(ParticipationEvent::getHistoryId)
                    .collect(Collectors.toList());
            try {
                int updated = participationHistoryRepository.bulkUpdateSuccess(validHistoryIds);
                log.info("SUCCESS 업데이트 완료. {}건", updated);
                writeResultCache(validEvents);
            } catch (Exception e) {
                log.error("배치 UPDATE 실패. 개별 처리로 전환. {}건", validEvents.size(), e);
                fallbackIndividual(validEvents);
            }
        }

        // ⑤ Kafka 오프셋 커밋
        // 오류가 발생한 메시지는 DLQ로 격리했으므로 무조건 커밋 (무한 재처리 방지)
        acknowledgment.acknowledge();
    }

    /**
     * Redis 결과 캐시 일괄 적재 (pipeline)
     * participation:result:{userId}:{campaignId} = "SUCCESS", TTL 300초
     * PollingController가 읽을 데이터 — DB 커밋 후 호출 (트랜잭션 밖)
     * 캐시 적재 실패는 무시 (DB가 이미 SUCCESS, DB fallback으로 조회 가능)
     */
    @SuppressWarnings("unchecked")
    private void writeResultCache(List<ParticipationEvent> events) {
        try {
            redisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                public <K, V> Object execute(RedisOperations<K, V> operations) {
                    RedisOperations<String, String> ops = (RedisOperations<String, String>) operations;
                    for (ParticipationEvent event : events) {
                        String key = RESULT_CACHE_PREFIX + event.getUserId() + ":" + event.getCampaignId();
                        ops.opsForValue().set(key, "SUCCESS", RESULT_CACHE_TTL);
                    }
                    return null;
                }
            });
            log.info("결과 캐시 적재 완료. {}건", events.size());
        } catch (Exception e) {
            log.error("결과 캐시 적재 실패 (무시 — DB는 이미 SUCCESS). {}건", events.size(), e);
        }
    }

    /**
     * 배치 UPDATE 실패 시 개별 처리
     * 개별 성공 → 결과 캐시 적재
     * 개별 실패 → Poison Pill DLQ + Slack 알림 후 ack 강행 (무한 재처리 방지)
     */
    private void fallbackIndividual(List<ParticipationEvent> events) {
        List<ParticipationEvent> successEvents = new ArrayList<>();

        for (ParticipationEvent event : events) {
            try {
                participationHistoryRepository.bulkUpdateSuccess(List.of(event.getHistoryId()));
                successEvents.add(event);
                log.info("개별 처리 성공. historyId={}", event.getHistoryId());
            } catch (Exception e) {
                log.error("개별 처리도 실패 (Poison Pill). historyId={}", event.getHistoryId(), e);
                sendToDlqWithSlack(event.getHistoryId().toString(), "INDIVIDUAL_UPDATE_FAILED", e);
            }
        }

        if (!successEvents.isEmpty()) {
            writeResultCache(successEvents);
        }
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
                    sendToDlqWithSlack(record.value(), "MISSING_HISTORY_ID", null);
                    continue;
                }
                events.add(event);
            } catch (Exception e) {
                log.error("JSON 파싱 실패 - DLQ 전송: {}", record.value(), e);
                sendToDlqWithSlack(record.value(), "JSON_PARSE_ERROR", e);
            }
        }
        return events;
    }

    /**
     * DLQ 전송 + Slack 알림
     * DLQ 전송 자체가 실패하면 로그로만 기록 (무한 루프 방지)
     */
    private void sendToDlqWithSlack(String originalMessage, String errorReason, Exception exception) {
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

        slackNotificationService.sendDlqAlert(
                "Consumer " + errorReason,
                originalMessage
        );
    }
}
