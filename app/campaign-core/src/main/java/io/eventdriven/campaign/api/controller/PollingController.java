package io.eventdriven.campaign.api.controller;

import io.eventdriven.campaign.api.common.ApiResponse;
import io.eventdriven.campaign.domain.entity.ParticipationHistory;
import io.eventdriven.campaign.domain.repository.ParticipationHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 202 후속 결과 조회 API (v2 폴링용)
 *
 * POST 참여요청 → 202 반환 후 클라이언트가 주기적으로 결과를 조회.
 * Redis cache 우선 조회 → 없으면 DB fallback.
 *
 * Redis 키: participation:result:{userId}:{campaignId}
 * - Consumer의 writeResultCache()에서 TTL 300초로 적재
 * - 캐시 미스 시 DB에서 직접 조회
 */
@Slf4j
@RestController
@RequestMapping("/api/campaigns")
@RequiredArgsConstructor
public class PollingController {

    private final RedisTemplate<String, String> redisTemplate;
    private final ParticipationHistoryRepository participationHistoryRepository;

    private static final String RESULT_CACHE_PREFIX = "participation:result:";

    /**
     * 참여 결과 조회
     * GET /api/campaigns/{campaignId}/participation/{userId}/result
     *
     * @return {"status": "PENDING" | "SUCCESS" | "FAIL"} 또는 404
     */
    @GetMapping("/{campaignId}/participation/{userId}/result")
    public ResponseEntity<ApiResponse<?>> getResult(
            @PathVariable Long campaignId,
            @PathVariable Long userId
    ) {
        // ① Redis cache 우선 조회
        String cacheKey = RESULT_CACHE_PREFIX + userId + ":" + campaignId;
        String cachedStatus = redisTemplate.opsForValue().get(cacheKey);

        if (cachedStatus != null) {
            log.debug("캐시 히트. userId={}, campaignId={}, status={}", userId, campaignId, cachedStatus);
            return ResponseEntity.ok(ApiResponse.success(Map.of("status", cachedStatus)));
        }

        // ② DB fallback
        return participationHistoryRepository.findByCampaignIdAndUserId(campaignId, userId)
                .map(history -> {
                    log.debug("DB fallback. userId={}, campaignId={}, status={}", userId, campaignId, history.getStatus());
                    return ResponseEntity.ok(
                            ApiResponse.success(Map.of("status", history.getStatus().name()))
                    );
                })
                .orElseGet(() -> {
                    log.warn("참여 이력 없음. userId={}, campaignId={}", userId, campaignId);
                    return ResponseEntity.status(404)
                            .body(ApiResponse.fail("HISTORY_NOT_FOUND", "참여 이력을 찾을 수 없습니다."));
                });
    }
}
