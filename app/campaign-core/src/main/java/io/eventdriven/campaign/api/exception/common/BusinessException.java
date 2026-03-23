package io.eventdriven.campaign.api.exception.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 비즈니스 예외 기본 클래스
 * - 4xx 계열 HTTP 상태 코드
 * - 클라이언트 측 오류 (잘못된 요청, 비즈니스 규칙 위반 등)
 */
@Getter
public abstract class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final HttpStatus httpStatus;
    private final String code;

    protected BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.httpStatus = errorCode.getHttpStatus();
        this.code = errorCode.getCode();
    }

    protected BusinessException(ErrorCode errorCode, String customMessage) {
        super(customMessage);
        this.errorCode = errorCode;
        this.httpStatus = errorCode.getHttpStatus();
        this.code = errorCode.getCode();
    }

    protected BusinessException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
        this.httpStatus = errorCode.getHttpStatus();
        this.code = errorCode.getCode();
    }

    protected BusinessException(ErrorCode errorCode, String customMessage, Throwable cause) {
        super(customMessage, cause);
        this.errorCode = errorCode;
        this.httpStatus = errorCode.getHttpStatus();
        this.code = errorCode.getCode();
    }
}
