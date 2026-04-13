package io.eventdriven.campaign.api.exception.infrastructure;

import io.eventdriven.campaign.api.exception.common.ErrorCode;
import io.eventdriven.campaign.api.exception.common.InfrastructureException;

public class ParticipationServiceUnavailableException extends InfrastructureException{
    public ParticipationServiceUnavailableException(Long campaignId, Long userId) {
        super(ErrorCode.PARTICIPATION_SERVICE_UNAVAILABLE,
                String.format("PENDING INSERT 재시도 전부 실패. campaignId=%d, userId=%d", campaignId, userId));
    }


}
