package io.eventdriven.campaign.application.service;

import io.eventdriven.campaign.api.exception.infrastructure.KafkaPublishException;
import io.eventdriven.campaign.api.exception.infrastructure.KafkaSerializationException;
import io.eventdriven.campaign.application.event.ParticipationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParticipationService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JsonMapper jsonMapper;

    private static final String TOPIC = "campaign-participation-topic";

    /**
     * 선착순 참여 요청 처리 (비동기 + 콜백)
     * - Kafka로 이벤트 발행
     * - 전송 결과를 비동기로 확인하여 실패 시 로깅 및 알림
     */
    public void participate(Long campaignId, Long userId) {
        ParticipationEvent event = new ParticipationEvent(campaignId, userId);

        try {
            // 1. JSON 직렬화
            String message = jsonMapper.writeValueAsString(event);

            // Key를 null로 설정하여 round-robin 방식으로 파티션 분산
            // 같은 campaignId를 key로 사용하면 모든 메시지가 같은 파티션으로 가서 순서 보장됨
            // null을 사용하면 3개 파티션에 균등 분산되어 순서가 섞임
            String key = null;

            // 2. Kafka 전송 (비동기 + 콜백)
            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(TOPIC, key, message);

            // 3. 전송 결과 콜백 처리
            future.whenComplete((result, ex) -> {
                if (ex != null) {
                    // 전송 실패
                    handleKafkaPublishFailure(campaignId, userId, message, ex);
                } else {
                    // 전송 성공
                    handleKafkaPublishSuccess(campaignId, userId, result);
                }
            });

        } catch (Exception e) {
            // JSON 직렬화 실패
            log.error("🚨 JSON 직렬화 실패 - Campaign ID: {}, User ID: {}", campaignId, userId, e);
            throw new KafkaSerializationException(e);
        }
    }

    /**
     * Kafka 전송 성공 처리
     */
    private void handleKafkaPublishSuccess(Long campaignId, Long userId, SendResult<String, String> result) {
        log.info("✅ Kafka 전송 성공 - Campaign ID: {}, User ID: {}, Offset: {}, Partition: {}",
                campaignId,
                userId,
                result.getRecordMetadata().offset(),
                result.getRecordMetadata().partition());

        // TODO: 메트릭 수집 (Prometheus 등)
        // meterRegistry.counter("kafka.publish.success", "topic", TOPIC).increment();
    }

    /**
     * Kafka 전송 실패 처리
     */
    private void handleKafkaPublishFailure(Long campaignId, Long userId, String message, Throwable ex) {
        log.error("🚨 Kafka 전송 실패 - Campaign ID: {}, User ID: {}, Message: {}",
                campaignId, userId, message, ex);

        // 운영자 알림 (실제 환경에서는 Slack, Email 등으로 전송)
        log.error("🔔 [ALERT] Kafka 전송 실패 - 데이터 손실 위험! Campaign ID: {}, User ID: {}",
                campaignId, userId);

        // TODO: 실패한 메시지를 별도 저장 (DB 또는 파일)
        // failureRepository.save(new KafkaFailureLog(campaignId, userId, message, ex.getMessage()));

        // TODO: 메트릭 수집
        // meterRegistry.counter("kafka.publish.failure", "topic", TOPIC).increment();

        // 사용자에게는 일반적인 오류 메시지를 던짐 (GlobalExceptionHandler가 처리)
        throw new KafkaPublishException(TOPIC, ex);
    }
}