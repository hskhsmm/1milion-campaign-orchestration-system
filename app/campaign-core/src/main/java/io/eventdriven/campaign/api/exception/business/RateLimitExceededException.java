package io.eventdriven.campaign.api.exception.business;

import io.eventdriven.campaign.api.exception.common.BusinessException;
import io.eventdriven.campaign.api.exception.common.ErrorCode;

public class RateLimitExceededException extends BusinessException {
    public RateLimitExceededException(Long campaignId, Long userId) {
        super(ErrorCode.RATE_LIMIT_EXCEEDED,
                String.format("중복 요청 차단. campaignId=%d, userId=%d", campaignId, userId));
    }

}
