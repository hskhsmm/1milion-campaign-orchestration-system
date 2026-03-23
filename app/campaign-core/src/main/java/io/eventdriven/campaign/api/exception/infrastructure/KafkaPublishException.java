package io.eventdriven.campaign.api.exception.infrastructure;

import io.eventdriven.campaign.api.exception.common.ErrorCode;
import io.eventdriven.campaign.api.exception.common.InfrastructureException;

/**
 * Kafka 메시지 발행 실패 시 발생하는 예외
 * HTTP 500 Internal Server Error
 */
public class KafkaPublishException extends InfrastructureException {

    public KafkaPublishException(String topic, Throwable cause) {
        super(ErrorCode.KAFKA_PUBLISH_FAILED,
              String.format("Kafka 메시지 발행에 실패했습니다. (토픽: %s)", topic),
              cause);
    }

    public KafkaPublishException(Throwable cause) {
        super(ErrorCode.KAFKA_PUBLISH_FAILED, cause);
    }

    public KafkaPublishException(String message) {
        super(ErrorCode.KAFKA_PUBLISH_FAILED, message);
    }
}
