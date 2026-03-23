package io.eventdriven.campaign.api.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class LoadTestRequest {

    @NotNull(message = "캠페인 ID는 필수입니다")
    private Long campaignId;

    /**
     * 총 요청 수 (1000, 10000, 30000, 100000 등)
     * 이 값을 기반으로 K6의 rate와 duration을 계산
     */
    @NotNull(message = "총 요청 수는 필수입니다")
    @Min(value = 100, message = "총 요청 수는 100 이상이어야 합니다")
    private Integer totalRequests = 30000; // 기본값 30000

    /**
     * Kafka 파티션 수 (1, 3, 10 등)
     * 파티션이 많을수록 병렬 처리량이 증가하지만 순서 보장 범위는 파티션 내로 제한됨
     */
    @NotNull(message = "파티션 수는 필수입니다")
    @Min(value = 1, message = "파티션 수는 1 이상이어야 합니다")
    private Integer partitions = 3; // 기본값 3

    // 하위 호환성을 위해 유지 (deprecated 예정)
    @Deprecated
    private Integer virtualUsers = 100; // 기본값 100

    @Deprecated
    private Integer duration = 5; // 기본값 5초
}
