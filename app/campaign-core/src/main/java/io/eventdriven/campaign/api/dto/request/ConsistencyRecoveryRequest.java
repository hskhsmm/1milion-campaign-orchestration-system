package io.eventdriven.campaign.api.dto.request;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ConsistencyRecoveryRequest {
    private String requestedBy;
    private Boolean dryRun;
    private Boolean autoFix;
    private Long campaignId;
    private Integer maxCampaigns;
}
