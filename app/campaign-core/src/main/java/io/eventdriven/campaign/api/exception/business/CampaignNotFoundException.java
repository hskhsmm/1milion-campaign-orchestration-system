package io.eventdriven.campaign.api.exception.business;

import io.eventdriven.campaign.api.exception.common.BusinessException;
import io.eventdriven.campaign.api.exception.common.ErrorCode;

/**
 * 캠페인을 찾을 수 없을 때 발생하는 예외
 * HTTP 404 Not Found
 */
public class CampaignNotFoundException extends BusinessException {

    public CampaignNotFoundException(Long campaignId) {
        super(ErrorCode.CAMPAIGN_NOT_FOUND,
              String.format("캠페인을 찾을 수 없습니다. (ID: %d)", campaignId));
    }

    public CampaignNotFoundException() {
        super(ErrorCode.CAMPAIGN_NOT_FOUND);
    }
}
