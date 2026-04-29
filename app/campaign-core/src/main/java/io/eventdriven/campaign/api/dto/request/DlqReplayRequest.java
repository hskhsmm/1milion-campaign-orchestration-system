package io.eventdriven.campaign.api.dto.request;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class DlqReplayRequest {
    private String requestedBy;
    private Boolean dryRun;
    private Long campaignId;
    private String reason;
    private LocalDateTime fromTime;
    private LocalDateTime toTime;
    private Integer maxItems;
}
