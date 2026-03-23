package io.eventdriven.campaign.api.controller;

import io.eventdriven.campaign.api.common.ApiResponse;
import io.eventdriven.campaign.api.dto.request.CampaignCreateRequest;
import io.eventdriven.campaign.api.dto.response.CampaignResponse;
import io.eventdriven.campaign.application.service.CampaignService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/campaigns")
@RequiredArgsConstructor
public class AdminController {

    private final CampaignService campaignService;

    @PostMapping
    public ResponseEntity<ApiResponse<CampaignResponse>> createCampaign(@RequestBody @Valid CampaignCreateRequest request) {
        CampaignResponse response = campaignService.createCampaign(request);
        return ResponseEntity.ok(
                ApiResponse.success("캠페인이 생성되었습니다.", response)
        );
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<CampaignResponse>>> getCampaigns() {
        return ResponseEntity.ok(
                ApiResponse.success(campaignService.getCampaigns())
        );
    }
}
