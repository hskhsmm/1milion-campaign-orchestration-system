package io.eventdriven.campaign.api.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoadTestMetrics {

    private Double p50;           // 50 percentile 응답 시간 (ms)
    private Double p95;           // 95 percentile 응답 시간 (ms)
    private Double p99;           // 99 percentile 응답 시간 (ms)
    private Double avg;           // 평균 응답 시간 (ms)
    private Double max;           // 최대 응답 시간 (ms)
    private Double min;           // 최소 응답 시간 (ms)
    private Integer totalRequests; // 총 요청 수
    private Double throughput;     // 처리량 (req/s)
    private Double failureRate;    // 실패율 (0.0 ~ 1.0)
}
