package io.eventdriven.campaign.api.exception.business;

import io.eventdriven.campaign.api.exception.common.BusinessException;
import io.eventdriven.campaign.api.exception.common.ErrorCode;

import java.time.LocalDate;

/**
 * 배치가 이미 실행되었을 때 발생하는 예외
 * HTTP 409 Conflict
 */
public class BatchAlreadyExecutedException extends BusinessException {

    public BatchAlreadyExecutedException(LocalDate date) {
        super(ErrorCode.BATCH_ALREADY_EXECUTED,
              String.format("해당 날짜는 이미 집계되었습니다. (날짜: %s)", date));
    }

    public BatchAlreadyExecutedException(String message) {
        super(ErrorCode.BATCH_ALREADY_EXECUTED, message);
    }
}
