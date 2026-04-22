package io.eventdriven.campaign.application.consumer;

import io.eventdriven.campaign.application.event.ParticipationEvent;
import io.eventdriven.campaign.application.service.SlackNotificationService;
import io.eventdriven.campaign.domain.repository.ParticipationHistoryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.util.concurrent.TimeUnit;

/**
 * 선착순 참여 이벤트 Consumer (v3)
 *
 * v3 역할: API에서 Redis DECR만 하고 DB 미접촉 → Consumer가 DB INSERT SUCCESS 직접 처리
 * - API: RateLimit → DECR → LPUSH → 202 (DB 없음)
 * - Consumer: (campaignId, userId, sequence) → INSERT SUCCESS
 * - 멱등성: UNIQUE(campaign_id, user_id) 제약 → DataIntegrityViolationException 무시
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
    private final MeterRegistry meterRegistry;

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

        // ① 메시지 파싱 — sequence 없는 메시지는 Poison Pill로 DLQ
        List<ParticipationEvent> events = parseRecords(records);

        if (events.isEmpty()) {
            acknowledgment.acknowledge();
            return;
        }

        // ② DB INSERT SUCCESS 직접 처리
        LocalDateTime batchStart = LocalDateTime.now();
        List<ParticipationEvent> successEvents = new ArrayList<>();

        for (ParticipationEvent event : events) {
            try {
                // INSERT IGNORE → UNIQUE 중복 시 조용히 무시 (멱등성)
                participationHistoryRepository.insertSuccess(
                        event.getCampaignId(), event.getUserId(), event.getSequence());
                successEvents.add(event);
                log.info("INSERT SUCCESS. campaignId={}, userId={}, sequence={}",
                        event.getCampaignId(), event.getUserId(), event.getSequence());

            } catch (DataIntegrityViolationException e) {
                // INSERT IGNORE로 대부분 처리되지만 혹시 모를 중복 예외 방어
                log.warn("중복 INSERT 무시 (멱등). campaignId={}, userId={}, sequence={}",
                        event.getCampaignId(), event.getUserId(), event.getSequence());
                successEvents.add(event);

            } catch (Exception e) {
                log.error("INSERT 실패 → DLQ. campaignId={}, userId={}, sequence={}",
                        event.getCampaignId(), event.getUserId(), event.getSequence(), e);
                sendToDlqWithSlack(buildMessageStr(event), "INSERT_FAILED", e);
            }
        }

        // ③ 지연시간 측정 (API LPUSH ~ Consumer INSERT 완료)
        long latencyMs = Duration.between(batchStart, LocalDateTime.now()).toMillis();
        Timer.builder("consumer.pending_to_success.latency")
                .description("API LPUSH ~ Consumer INSERT SUCCESS 지연시간")
                .register(meterRegistry)
                .record(latencyMs, TimeUnit.MILLISECONDS);

        log.info("배치 처리 완료. 성공={}건, 지연={}ms", successEvents.size(), latencyMs);

        // ④ 결과 캐시 적재 (DB 커밋 후)
        if (!successEvents.isEmpty()) {
            writeResultCache(successEvents);
        }

        // ⑤ Kafka 오프셋 커밋
        acknowledgment.acknowledge();
    }

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

    private List<ParticipationEvent> parseRecords(List<ConsumerRecord<String, String>> records) {
        List<ParticipationEvent> events = new ArrayList<>();
        for (ConsumerRecord<String, String> record : records) {
            try {
                ParticipationEvent event = jsonMapper.readValue(record.value(), ParticipationEvent.class);
                if (event.getSequence() == null) {
                    log.warn("sequence 없는 메시지 (Poison Pill) - DLQ 전송: {}", record.value());
                    sendToDlqWithSlack(record.value(), "MISSING_SEQUENCE", null);
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
        slackNotificationService.sendDlqAlert("Consumer " + errorReason, originalMessage);
    }

    private String buildMessageStr(ParticipationEvent event) {
        return String.format("{campaignId:%d, userId:%d, sequence:%d}",
                event.getCampaignId(), event.getUserId(), event.getSequence());
    }
}
