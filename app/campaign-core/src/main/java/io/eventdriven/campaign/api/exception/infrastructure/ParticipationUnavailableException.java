package io.eventdriven.campaign.api.exception.infrastructure;

import io.eventdriven.campaign.api.exception.common.ErrorCode;
import io.eventdriven.campaign.api.exception.common.InfrastructureException;

/**
 * 재고 차감 Lua를 호출하기 전 Redis 오류로 참여를 처리하지 못했을 때 발생한다.
 * 재고와 대기열은 바뀌지 않았으므로 미접수이며 다시 시도할 수 있다.
 */
public class ParticipationUnavailableException extends InfrastructureException {

    public ParticipationUnavailableException(Long campaignId, Throwable cause) {
        super(ErrorCode.PARTICIPATION_SERVICE_UNAVAILABLE,
                String.format("참여 처리 불가(미접수). campaignId=%d", campaignId),
                cause);
    }
}
