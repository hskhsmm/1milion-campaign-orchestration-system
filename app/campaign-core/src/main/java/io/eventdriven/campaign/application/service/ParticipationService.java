package io.eventdriven.campaign.application.service;

import io.eventdriven.campaign.api.exception.business.DuplicateParticipationException;
import io.eventdriven.campaign.api.exception.business.QueueFullException;
import io.eventdriven.campaign.api.exception.business.RateLimitExceededException;
import io.eventdriven.campaign.api.exception.business.StockExhaustedException;
import io.eventdriven.campaign.api.exception.infrastructure.ParticipationResultUnknownException;
import io.eventdriven.campaign.api.exception.infrastructure.ParticipationUnavailableException;
import io.eventdriven.campaign.domain.entity.CampaignStatus;
import io.eventdriven.campaign.domain.repository.CampaignRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParticipationService {
    private final CampaignRepository campaignRepository;
    private final RateLimitService rateLimitService;
    private final RedisStockService redisStockService;

    public void participate(Long campaignId, Long userId) {
        boolean allowed;
        try {
            allowed = rateLimitService.isAllowed(campaignId, userId);
        } catch (QueryTimeoutException | RedisConnectionFailureException e) {
            // Lua 호출 전이라 재고·대기열은 바뀌지 않았다.
            releaseRateLimitQuietly(campaignId, userId);
            throw new ParticipationUnavailableException(campaignId, e);
        }
        if (!allowed) {
            throw new RateLimitExceededException(campaignId, userId);
        }

        // 재고 차감 + 큐 적재 단일 Lua 원자 실행 — partial failure 원천 차단
        long[] result;
        try {
            result = redisStockService.checkDecrEnqueue(campaignId, userId);
        } catch (QueryTimeoutException | RedisConnectionFailureException e) {
            // 응답만 못 받았을 수 있다. 실패로 단정하거나 보상 차감하지 않는다.
            // 재시도가 Rate Limit에 막히지 않게 키를 풀고, 중복은 Lua의 참여 키가 막는다.
            releaseRateLimitQuietly(campaignId, userId);
            throw new ParticipationResultUnknownException(campaignId, e);
        }
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
        log.debug("[ATOMIC] campaignId={} userId={} sequence={} remaining={}",
                campaignId, userId, sequence, remaining);
    }

    private void releaseRateLimitQuietly(Long campaignId, Long userId) {
        try {
            rateLimitService.release(campaignId, userId);
        } catch (RuntimeException e) {
            // 키가 남아도 TTL(10초) 뒤 재시도할 수 있으므로 원래 오류 응답을 유지한다.
            log.warn("Rate Limit 키 해제 실패. campaignId={}", campaignId);
        }
    }
}
