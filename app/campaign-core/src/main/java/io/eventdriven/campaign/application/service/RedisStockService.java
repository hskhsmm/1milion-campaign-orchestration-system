package io.eventdriven.campaign.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@SuppressWarnings("rawtypes")

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisStockService {

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<List> checkDecrTotalScript;

    private static final String ACTIVE_CAMPAIGNS_KEY = "active:campaigns";          // Bridge SMEMBERS 순회용 전역 Set (Lua 밖에서만 사용)
    private static final String ACTIVE_FLAG_KEY_PREFIX = "active:campaign:{";        // Lua용 캠페인별 플래그 (해시태그로 stock/total과 동일 슬롯)
    private static final String STOCK_KEY_PREFIX = "stock:campaign:{";               // 해시태그 포함 — Redis Cluster 슬롯 통일
    private static final String TOTAL_KEY_PREFIX = "total:campaign:{";               // 해시태그 포함 — Redis Cluster 슬롯 통일
    public static final Long INACTIVE_CAMPAIGN = -999L;


    /**
     * 캠페인 재고 초기화
     * 캠페인 생성/시작 시 MySQL의 재고를 Redis에 동기화
     *
     * @param campaignId 캠페인 ID
     * @param stock 초기 재고 수량
     */

    // 캠페인 새롭게 생성시 재고 초기화, 및 캠페인 ID로 키값 생성 후 , 레디스 서버와 통신하여 재고 수 초기화.
    public void initializeStock(Long campaignId, Long stock) {
        String key = getStockKey(campaignId); // 키 생성
        redisTemplate.opsForValue().set(key, String.valueOf(stock)); // 스트링 타입으로 키-값 셋(캠페인, 재고)
        log.info("Redis 재고 초기화 - Campaign: {}, Stock: {}", campaignId, stock);
    }



    // INCR 보상용 — DuplicateKey + 다른 sequence 케이스에만 사용
    public void incrementStock(Long campaignId) {
        String key = getStockKey(campaignId);
        redisTemplate.opsForValue().increment(key);
    }

    // 캠페인 활성화 — Bridge 순회용 전역 Set + Lua용 캠페인별 플래그 둘 다 등록
    public void activateCampaign(Long campaignId) {
        redisTemplate.opsForSet().add(ACTIVE_CAMPAIGNS_KEY, campaignId.toString());
        redisTemplate.opsForValue().set(getActiveFlagKey(campaignId), "1");
    }

    // 캠페인 비활성화 — Bridge 순회용 전역 Set + Lua용 캠페인별 플래그 둘 다 정리
    public void deactivateCampaign(Long campaignId) {
        redisTemplate.opsForSet().remove(ACTIVE_CAMPAIGNS_KEY, campaignId.toString());
        redisTemplate.delete(getActiveFlagKey(campaignId));
    }

    // Bridge cleanup 판단용 — active flag 존재 여부 확인
    public boolean isActive(Long campaignId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(getActiveFlagKey(campaignId)));
    }

    /**
     * EXISTS + DECR + DEL(remaining==0) + GET total 원자 실행 (Lua)
     * returns long[]{remaining, total}
     * remaining == INACTIVE_CAMPAIGN(-999): 비활성 캠페인
     */
    @SuppressWarnings("unchecked")
    public long[] checkDecrTotal(Long campaignId) {
        List<Long> result = (List<Long>) redisTemplate.execute(
            checkDecrTotalScript,
            List.of(getActiveFlagKey(campaignId), getStockKey(campaignId), getTotalKey(campaignId))
        );
        if (result == null || result.size() < 2) {
            throw new IllegalStateException("checkDecrTotal 스크립트 오류. campaignId=" + campaignId);
        }
        return new long[]{result.get(0), result.get(1)};
    }

    // 캠페인 생성 시 totalStock Redis 저장 (sequence 계산 목적, DB findById 대체)
    public void initializeTotal(Long campaignId, Long totalStock) {
        redisTemplate.opsForValue().set(getTotalKey(campaignId), String.valueOf(totalStock));
    }

    private String getTotalKey(Long campaignId) {
        return TOTAL_KEY_PREFIX + campaignId + "}";
    }

    private String getActiveFlagKey(Long campaignId) {
        return ACTIVE_FLAG_KEY_PREFIX + campaignId + "}";
    }


    /**
     * 현재 재고 조회
     *
     * @param campaignId 캠페인 ID
     * @return 현재 재고 (키 없으면 null)
     */
    public Long getStock(Long campaignId) {
        String key = getStockKey(campaignId);
        String stock = redisTemplate.opsForValue().get(key);
        return stock != null ? Long.parseLong(stock) : null;
    }

    /**
     * 재고 키 삭제
     * 캠페인 종료 시 정리용
     *
     * @param campaignId 캠페인 ID
     */
    public void deleteStock(Long campaignId) {
        String key = getStockKey(campaignId);
        redisTemplate.delete(key);
        log.info("Redis 재고 삭제 - Campaign: {}", campaignId);
    }

    /**
     * 재고 키가 존재하는지 확인
     *
     * @param campaignId 캠페인 ID
     * @return 존재 여부
     */
    public boolean hasStock(Long campaignId) {
        String key = getStockKey(campaignId);
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    private String getStockKey(Long campaignId) {
        return STOCK_KEY_PREFIX + campaignId + "}";
    }
}
