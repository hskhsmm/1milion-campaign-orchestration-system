package io.eventdriven.campaign.application.service;

import io.eventdriven.campaign.api.dto.request.CampaignCreateRequest;
import io.eventdriven.campaign.api.dto.response.CampaignResponse;
import io.eventdriven.campaign.domain.entity.Campaign;
import io.eventdriven.campaign.domain.repository.CampaignRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
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
    private final RedisTemplate<String, String> redisTemplate;

    @Transactional
    public CampaignResponse createCampaign(CampaignCreateRequest request) {
        Campaign campaign = new Campaign(request.getName(), request.getTotalStock());
        Campaign savedCampaign = campaignRepository.save(campaign);

        // Redis에 재고 초기화
        redisStockService.initializeStock(savedCampaign.getId(), request.getTotalStock());
        redisStockService.initializeTotal(savedCampaign.getId(), request.getTotalStock());
        redisTemplate.opsForSet().add("active:campaigns", savedCampaign.getId().toString());




        return new CampaignResponse(savedCampaign);
    }

    public List<CampaignResponse> getCampaigns() {
        return campaignRepository.findAll().stream()
                .map(CampaignResponse::new)
                .collect(Collectors.toList());
    }
}
