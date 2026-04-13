package io.eventdriven.campaign.api.exception.business;

import io.eventdriven.campaign.api.exception.common.BusinessException;
import io.eventdriven.campaign.api.exception.common.ErrorCode;

public class DuplicateParticipationException extends BusinessException {

    public DuplicateParticipationException(Long campaignId, Long userId) {
        super(ErrorCode.DUPLICATE_PARTICIPATION,
                String.format("이미 참여한 캠페인입니다. campaignId=%d, userId=%d", campaignId, userId));
    }
}
