package io.eventdriven.campaign.api.exception.infrastructure;

import io.eventdriven.campaign.api.exception.common.ErrorCode;
import io.eventdriven.campaign.api.exception.common.InfrastructureException;

/**
 * Kafka 메시지 직렬화 실패 시 발생하는 예외
 * HTTP 500 Internal Server Error
 */
public class KafkaSerializationException extends InfrastructureException {

    public KafkaSerializationException(Throwable cause) {
        super(ErrorCode.KAFKA_SERIALIZATION_FAILED,
              "Kafka 메시지 직렬화에 실패했습니다.",
              cause);
    }

    public KafkaSerializationException(String message, Throwable cause) {
        super(ErrorCode.KAFKA_SERIALIZATION_FAILED, message, cause);
    }
}
