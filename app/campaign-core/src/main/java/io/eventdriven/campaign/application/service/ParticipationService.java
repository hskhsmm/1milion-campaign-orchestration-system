package io.eventdriven.campaign.application.service;

import io.eventdriven.campaign.api.exception.business.RateLimitExceededException;
import io.eventdriven.campaign.api.exception.business.StockExhaustedException;
import io.eventdriven.campaign.domain.entity.CampaignStatus;
import io.eventdriven.campaign.domain.repository.CampaignRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParticipationService {
    private final CampaignRepository campaignRepository;
    private final RateLimitService rateLimitService;
    private final RedisQueueService redisQueueService;
    private final RedisStockService redisStockService;
    private final JsonMapper jsonMapper;

    public void participate(Long campaignId, Long userId) {
        if (!rateLimitService.isAllowed(campaignId, userId)) {
            throw new RateLimitExceededException(campaignId, userId);
        }

        // SISMEMBER + DECR + GET total 원자 실행 — 비활성/소진 요청은 DB 미접촉
        long[] stockResult = redisStockService.checkDecrTotal(campaignId);
        long remaining = stockResult[0];
        long total     = stockResult[1];

        if (remaining == RedisStockService.INACTIVE_CAMPAIGN) {
            throw new StockExhaustedException(campaignId);
        }
        if (remaining < 0) {
            throw new StockExhaustedException(campaignId);
        }

        if (remaining == 0) {
            campaignRepository.closeAndResetStock(campaignId, CampaignStatus.CLOSED);
            log.info("캠페인 자동 종료. campaignId={}", campaignId);
        }

        // sequence 확정 — Redis DECR 결과로 선착순 번호 원자적 결정
        long sequence = total - remaining;

        // DB INSERT 없음 — Consumer가 sequence 기반으로 직접 INSERT SUCCESS 처리
        long t0 = System.currentTimeMillis();
        String message = buildMessage(campaignId, userId, sequence);
        boolean pushed = redisQueueService.push(campaignId, message);
        long redisPushMs = System.currentTimeMillis() - t0;

        log.info("[TIMING] campaignId={} userId={} sequence={} REDIS_PUSH={}ms",
                campaignId, userId, sequence, redisPushMs);

        if (!pushed) {
            log.warn("Redis Queue 적재 실패. campaignId={}, userId={}, sequence={}",
                    campaignId, userId, sequence);
        }
    }

    private String buildMessage(Long campaignId, Long userId, Long sequence) {
        try {
            Map<String, Object> msg = new HashMap<>();
            msg.put("campaignId", campaignId);
            msg.put("userId", userId);
            msg.put("sequence", sequence);
            return jsonMapper.writeValueAsString(msg);
        } catch (Exception e) {
            log.error("메시지 직렬화 실패. campaignId={}, userId={}, sequence={}", campaignId, userId, sequence, e);
            throw new RuntimeException("메시지 직렬화 실패", e);
        }
    }
}
