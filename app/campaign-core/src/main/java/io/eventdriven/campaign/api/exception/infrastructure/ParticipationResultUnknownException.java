package io.eventdriven.campaign.api.exception.infrastructure;

import io.eventdriven.campaign.api.exception.common.ErrorCode;
import io.eventdriven.campaign.api.exception.common.InfrastructureException;

/**
 * 재고 차감 Lua의 응답을 받지 못해 접수 여부를 알 수 없을 때 발생한다.
 * Lua는 이미 Redis에서 실행됐을 수 있으므로 실패로 단정하지 않는다.
 * 같은 요청을 다시 보내면 Lua의 참여 키 검사로 중복 없이 결과가 확정된다.
 * 202 또는 409(이미 접수)를 받으면 접수된 것이다.
 */
public class ParticipationResultUnknownException extends InfrastructureException {

    public ParticipationResultUnknownException(Long campaignId, Throwable cause) {
        super(ErrorCode.PARTICIPATION_RESULT_UNKNOWN,
                String.format("참여 결과 불명. campaignId=%d", campaignId),
                cause);
    }
}
