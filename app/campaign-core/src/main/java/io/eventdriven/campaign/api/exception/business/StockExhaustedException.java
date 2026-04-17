package io.eventdriven.campaign.api.exception.business;

import io.eventdriven.campaign.api.exception.common.BusinessException;
import io.eventdriven.campaign.api.exception.common.ErrorCode;

public class StockExhaustedException extends BusinessException {

    public StockExhaustedException(Long campaignId) {
        super(ErrorCode.STOCK_EXHAUSTED,
                String.format("재고 소진. campaignId=%d", campaignId));
    }

}
