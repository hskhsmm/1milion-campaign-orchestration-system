package io.eventdriven.campaign.application.service;

import io.eventdriven.campaign.api.dto.request.CampaignCreateRequest;
import io.eventdriven.campaign.api.dto.response.CampaignResponse;
import io.eventdriven.campaign.domain.entity.Campaign;
import io.eventdriven.campaign.domain.repository.CampaignRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CampaignService {

    private final CampaignRepository campaignRepository;
    private final RedisStockService redisStockService;

    @Transactional
    public CampaignResponse createCampaign(CampaignCreateRequest request) {
        Campaign campaign = new Campaign(request.getName(), request.getTotalStock());
        Campaign savedCampaign = campaignRepository.save(campaign);

        // Redis에 재고 초기화 + 캠페인 활성화 (전역 Set + 캠페인별 플래그 둘 다)
        redisStockService.initializeStock(savedCampaign.getId(), request.getTotalStock());
        redisStockService.initializeTotal(savedCampaign.getId(), request.getTotalStock());
        redisStockService.activateCampaign(savedCampaign.getId());




        return new CampaignResponse(savedCampaign);
    }

    public List<CampaignResponse> getCampaigns() {
        return campaignRepository.findAll().stream()
                .map(CampaignResponse::new)
                .collect(Collectors.toList());
    }
}
