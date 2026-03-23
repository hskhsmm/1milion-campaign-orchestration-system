package io.eventdriven.campaign.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;

/**
 * Redis 기반 재고 관리 서비스
 *
 * DB 병목 해결을 위해 재고 차감을 Redis 인메모리에서 처리
 * - Lua 스크립트의 원자성으로 동시성 문제 해결
 * - 인메모리 연산으로 디스크 I/O 제거
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisStockService {

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<Long> decreaseStockScript;

    private static final String STOCK_KEY_PREFIX = "stock:campaign:";

    /**
     * 캠페인 재고 초기화
     * 캠페인 생성/시작 시 MySQL의 재고를 Redis에 동기화
     *
     * @param campaignId 캠페인 ID
     * @param stock 초기 재고 수량
     */
    public void initializeStock(Long campaignId, Long stock) {
        String key = getStockKey(campaignId);
        redisTemplate.opsForValue().set(key, String.valueOf(stock));
        log.info("Redis 재고 초기화 - Campaign: {}, Stock: {}", campaignId, stock);
    }

    /**
     * 재고 차감 (원자적 연산)
     * Lua 스크립트로 재고 확인 + 차감을 원자적으로 처리
     *
     * @param campaignId 캠페인 ID
     * @return 차감 후 남은 재고 (0 이상: 성공, -1: 실패)
     */
    public Long decreaseStock(Long campaignId) {
        String key = getStockKey(campaignId);
        Long remainingStock = redisTemplate.execute(
                decreaseStockScript,
                Collections.singletonList(key)
        );
        return remainingStock != null ? remainingStock : -1L;
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
        return STOCK_KEY_PREFIX + campaignId;
    }
}
