package io.eventdriven.campaign.api.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoadTestResult {

    private String jobId;          // 작업 ID
    private String method;         // KAFKA | SYNC
    private Long campaignId;       // 캠페인 ID
    private String status;         // RUNNING | COMPLETED | FAILED
    private LoadTestMetrics metrics; // 성능 메트릭
    private String error;          // 에러 메시지 (실패 시)
    private String completedAt;    // 완료 시간
}
