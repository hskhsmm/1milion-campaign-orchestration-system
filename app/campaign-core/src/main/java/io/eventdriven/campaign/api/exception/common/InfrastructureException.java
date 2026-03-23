package io.eventdriven.campaign.api.exception.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 인프라 예외 기본 클래스
 * - 5xx 계열 HTTP 상태 코드
 * - 서버 측 오류 (Kafka, DB, 외부 API 등)
 * - 알림이 필요한 예외 (운영자 개입 필요)
 */
@Getter
public abstract class InfrastructureException extends RuntimeException {

    private final ErrorCode errorCode;
    private final HttpStatus httpStatus;
    private final String code;

    protected InfrastructureException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.httpStatus = errorCode.getHttpStatus();
        this.code = errorCode.getCode();
    }

    protected InfrastructureException(ErrorCode errorCode, String customMessage) {
        super(customMessage);
        this.errorCode = errorCode;
        this.httpStatus = errorCode.getHttpStatus();
        this.code = errorCode.getCode();
    }

    protected InfrastructureException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
        this.httpStatus = errorCode.getHttpStatus();
        this.code = errorCode.getCode();
    }

    protected InfrastructureException(ErrorCode errorCode, String customMessage, Throwable cause) {
        super(customMessage, cause);
        this.errorCode = errorCode;
        this.httpStatus = errorCode.getHttpStatus();
        this.code = errorCode.getCode();
    }

    /**
     * 이 예외가 알림을 필요로 하는지 여부
     * (기본값: true, 인프라 오류는 보통 운영자 개입 필요)
     */
    public boolean requiresAlert() {
        return true;
    }
}
