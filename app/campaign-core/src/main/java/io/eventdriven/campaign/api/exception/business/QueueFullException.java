package io.eventdriven.campaign.api.exception.business;

import io.eventdriven.campaign.api.exception.common.BusinessException;
import io.eventdriven.campaign.api.exception.common.ErrorCode;

public class QueueFullException extends BusinessException {
    public QueueFullException(Long campaignId) {
        super(ErrorCode.QUEUE_FULL,
                String.format("처리 큐 만원. campaignId=%d", campaignId));
    }
}
