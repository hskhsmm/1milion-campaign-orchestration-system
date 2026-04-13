package io.eventdriven.campaign.application.service;

import io.eventdriven.campaign.api.exception.business.CampaignNotFoundException;
import io.eventdriven.campaign.api.exception.business.DuplicateParticipationException;
import io.eventdriven.campaign.api.exception.business.RateLimitExceededException;
import io.eventdriven.campaign.api.exception.business.StockExhaustedException;
import io.eventdriven.campaign.api.exception.infrastructure.ParticipationServiceUnavailableException;
import io.eventdriven.campaign.domain.entity.Campaign;
import io.eventdriven.campaign.domain.entity.ParticipationHistory;
import io.eventdriven.campaign.domain.repository.CampaignRepository;
import io.eventdriven.campaign.domain.repository.ParticipationHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParticipationService {
    private final CampaignRepository campaignRepository;
    private final ParticipationHistoryRepository participationHistoryRepository;
    private final RateLimitService rateLimitService;
    private final RedisQueueService redisQueueService;
    private final RedisStockService redisStockService;
    private final JsonMapper jsonMapper;

    private static final int MAX_RETRY = 3;


    public void participate(Long campaignId, Long userId){
        //  RateLimit: SET NX EX 10 — 10초 내 동일 요청 차단
        if(!rateLimitService.isAllowed(campaignId, userId)) {
            throw new RateLimitExceededException(campaignId, userId);
        }
        // 캠페인 조회 — sequence 계산에 totalStock 필요
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new CampaignNotFoundException(campaignId));


        // Redis DECR — 원자적 재고 차감
        Long remaining = redisStockService.decreaseStock(campaignId);
        if (remaining < 0) {
            redisStockService.incrementStock(campaignId); // 보상 INCR
            throw new StockExhaustedException(campaignId);
        }

        // sequence = totalStock - remaining (선착순 번호, DECR 시점에 원자적 확정)
        long sequence = campaign.getTotalStock() - remaining;

        //  PENDING INSERT  (지수 백오프 재시도)
        Long historyId = insertPendingWithRetry(campaign, userId, sequence, campaignId);

        //  Redis Queue LPUSH: Bridge가 RPOP 후 Kafka 발행
        String message = buildMessage(campaignId, userId, historyId);
        boolean pushed = redisQueueService.push(campaignId, message);
        if (!pushed) {
            // Queue 100,000 초과: PENDING은 DB에 있으므로 Spring Batch 안전망이 처리
            log.warn("Redis Queue 적재 실패 (Spring Batch 안전망). campaignId={}, userId={}, historyId={}",
                    campaignId, userId, historyId);
        }
    }




    private Long insertPendingWithRetry(Campaign campaign, Long userId, long sequence, Long campaignId) {
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {                                                                                                                                                                                 ParticipationHistory history = new ParticipationHistory(campaign, userId, sequence);                                                                                              ParticipationHistory saved = participationHistoryRepository.save(history);
                log.info("PENDING INSERT 성공. campaignId={}, userId={}, historyId={}, attempt={}",                                                                                                       campaignId, userId, saved.getId(), attempt);
                return saved.getId();

            } catch (DataIntegrityViolationException e) {
                // UNIQUE 제약 위반 (campaign_id + user_id 중복)
                Optional<ParticipationHistory> existing =
                        participationHistoryRepository.findByCampaignIdAndUserId(campaignId, userId);

                if (existing.isPresent() && !existing.get().getSequence().equals(sequence)) {
                    // TTL 만료 후 새 요청: 이미 다른 sequence로 참여 완료
                    // 이번 DECR 보상 후 409
                    redisStockService.incrementStock(campaignId);
                    log.warn("TTL 만료 재요청. campaignId={}, userId={}, newSeq={}, existingSeq={}",
                            campaignId, userId, sequence, existing.get().getSequence());
                    throw new DuplicateParticipationException(campaignId, userId);
                }

                // 같은 sequence: 이전 시도에서 이미 INSERT 완료, 기존 historyId 반환
                log.warn("DuplicateKey 동일 sequence. campaignId={}, userId={}, historyId={}",
                        campaignId, userId, existing.get().getId());
                return existing.get().getId();

            } catch (Exception e) {
                // DB 타임아웃, 연결 실패 등 일반 장애 — 재시도
                log.warn("INSERT 실패. attempt={}/{}, campaignId={}, userId={}",
                        attempt, MAX_RETRY, campaignId, userId, e);

                if (attempt < MAX_RETRY) {
                    // exponential backoff: attempt=1 > 200ms, attempt=2 > 400ms
                    long backoffMs = (long) Math.pow(2, attempt) * 100L;
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        redisStockService.incrementStock(campaignId);
                        throw new ParticipationServiceUnavailableException(campaignId, userId);
                    }
                }
            }
        }

        // MAX_RETRY 3회 소진 — 보상 INCR + 503
        redisStockService.incrementStock(campaignId);
        throw new ParticipationServiceUnavailableException(campaignId, userId);
    }



    private String buildMessage(Long campaignId, Long userId, Long historyId) {
        try {
            Map<String, Object> msg = new HashMap<>();
            msg.put("campaignId", campaignId);
            msg.put("userId", userId);
            msg.put("historyId", historyId);
            return jsonMapper.writeValueAsString(msg);
        } catch (Exception e) {
            log.error("메시지 직렬화 실패. campaignId={}, userId={}, historyId={}", campaignId, userId, historyId, e);
            throw new RuntimeException("메시지 직렬화 실패", e);
        }
    }

}
