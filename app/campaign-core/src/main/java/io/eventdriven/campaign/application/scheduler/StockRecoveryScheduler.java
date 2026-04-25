package io.eventdriven.campaign.application.scheduler;

import io.eventdriven.campaign.application.service.RedisStockService;
import io.eventdriven.campaign.domain.entity.Campaign;
import io.eventdriven.campaign.domain.entity.CampaignStatus;
import io.eventdriven.campaign.domain.entity.ParticipationStatus;
import io.eventdriven.campaign.domain.repository.CampaignRepository;
import io.eventdriven.campaign.domain.repository.ParticipationHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class StockRecoveryScheduler {

    private final CampaignRepository campaignRepository;
    private final ParticipationHistoryRepository participationHistoryRepository;
    private final RedisStockService redisStockService;

    // Redis 재시작 후 stock 키 유실 감지 → DB 기반으로 재동기화
    @Scheduled(fixedDelay = 60_000)
    public void syncRedisStockFromDb() {
        List<Campaign> activeCampaigns = campaignRepository.findByStatus(CampaignStatus.OPEN);

        for (Campaign campaign : activeCampaigns) {
            if (redisStockService.hasStock(campaign.getId())) {
                continue;
            }

            long successCount = participationHistoryRepository
                    .countByCampaignIdAndStatus(campaign.getId(), ParticipationStatus.SUCCESS);
            long pendingCount = participationHistoryRepository
                    .countByCampaignIdAndStatus(campaign.getId(), ParticipationStatus.PENDING);
            long restoreStock = Math.max(campaign.getTotalStock() - successCount - pendingCount, 0);

            redisStockService.initializeStock(campaign.getId(), restoreStock);
            redisStockService.initializeTotal(campaign.getId(), campaign.getTotalStock());
            redisStockService.activateCampaign(campaign.getId());

            log.warn("Redis 재고 복구. campaignId={}, restoreStock={}, success={}, pending={}",
                    campaign.getId(), restoreStock, successCount, pendingCount);
        }
    }
}
