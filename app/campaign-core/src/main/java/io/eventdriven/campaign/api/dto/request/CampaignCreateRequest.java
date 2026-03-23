package io.eventdriven.campaign.api.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CampaignCreateRequest {

    @NotBlank(message = "캠페인 이름은 필수입니다.")
    private String name;

    @NotNull(message = "총 재고 수량은 필수입니다.")
    @Min(value = 1, message = "재고는 최소 1개 이상이어야 합니다.")
    private Long totalStock;

    public CampaignCreateRequest(String name, Long totalStock) {
        this.name = name;
        this.totalStock = totalStock;
    }
}
