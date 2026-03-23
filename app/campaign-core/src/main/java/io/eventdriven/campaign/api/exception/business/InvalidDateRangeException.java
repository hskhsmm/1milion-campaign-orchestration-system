package io.eventdriven.campaign.api.exception.business;

import io.eventdriven.campaign.api.exception.common.BusinessException;
import io.eventdriven.campaign.api.exception.common.ErrorCode;

/**
 * 날짜 범위가 유효하지 않을 때 발생하는 예외
 * HTTP 400 Bad Request
 */
public class InvalidDateRangeException extends BusinessException {

    public InvalidDateRangeException(String message) {
        super(ErrorCode.INVALID_DATE_RANGE, message);
    }

    public InvalidDateRangeException() {
        super(ErrorCode.INVALID_DATE_RANGE);
    }
}
