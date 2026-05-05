package io.eventdriven.campaign.application.service;

import io.eventdriven.campaign.api.exception.business.DuplicateParticipationException;
import io.eventdriven.campaign.api.exception.business.QueueFullException;
import io.eventdriven.campaign.api.exception.business.RateLimitExceededException;
import io.eventdriven.campaign.api.exception.business.StockExhaustedException;
import io.eventdriven.campaign.domain.entity.CampaignStatus;
import io.eventdriven.campaign.domain.repository.CampaignRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParticipationService {
    private final CampaignRepository campaignRepository;
    private final RateLimitService rateLimitService;
    private final RedisStockService redisStockService;

    public void participate(Long campaignId, Long userId) {
        if (!rateLimitService.isAllowed(campaignId, userId)) {
            throw new RateLimitExceededException(campaignId, userId);
        }

        // 재고 차감 + 큐 적재 단일 Lua 원자 실행 — partial failure 원천 차단
        long[] result = redisStockService.checkDecrEnqueue(campaignId, userId);
        long remaining = result[0];
        long total     = result[1];

        if (remaining == RedisStockService.INACTIVE_CAMPAIGN) {
            throw new StockExhaustedException(campaignId);
        }
        if (remaining == RedisStockService.ALREADY_PARTICIPATED) {
            throw new DuplicateParticipationException(campaignId, userId);
        }
        if (remaining == RedisStockService.QUEUE_FULL) {
            rateLimitService.release(campaignId, userId);
            throw new QueueFullException(campaignId);
        }
        if (remaining < 0) {
            throw new StockExhaustedException(campaignId);
        }

        if (remaining == 0) {
            try {
                campaignRepository.closeAndResetStock(campaignId, CampaignStatus.CLOSED);
                log.info("캠페인 자동 종료. campaignId={}", campaignId);
            } catch (Exception e) {
                log.error("캠페인 DB 종료 실패. Redis는 이미 비활성화됨. ConsistencyJob이 복구 예정. campaignId={}", campaignId, e);
            }
        }

        long sequence = total - remaining;
        log.info("[ATOMIC] campaignId={} userId={} sequence={} remaining={}",
                campaignId, userId, sequence, remaining);
    }
}
