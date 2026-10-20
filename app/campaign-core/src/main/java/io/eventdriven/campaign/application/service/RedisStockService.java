package io.eventdriven.campaign.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@SuppressWarnings("rawtypes")

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisStockService {

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<List> checkDecrEnqueueScript;

    private static final String ACTIVE_CAMPAIGNS_KEY = "active:campaigns";          // Bridge SMEMBERS 순회용 전역 Set (Lua 밖에서만 사용)
    private static final String ACTIVE_FLAG_KEY_PREFIX = "active:campaign:{";        // Lua용 캠페인별 플래그 (해시태그로 stock/total과 동일 슬롯)
    private static final String STOCK_KEY_PREFIX = "stock:campaign:{";               // 해시태그 포함 — Redis Cluster 슬롯 통일
    private static final String TOTAL_KEY_PREFIX = "total:campaign:{";               // 해시태그 포함 — Redis Cluster 슬롯 통일
    private static final String QUEUE_KEY_PREFIX = "queue:campaign:{";               // 해시태그 포함 — Lua 원자화 필수 조건
    private static final String PARTICIPATED_KEY_PREFIX = "participated:campaign:{"; // 해시태그 포함 — 중복 참여 방지
    private static final String STATE_LOCK_KEY_PREFIX = "lock:campaign:{";
    private static final Duration STATE_LOCK_LEASE = Duration.ofSeconds(60);
    private static final long STATE_LOCK_WAIT_NANOS = Duration.ofSeconds(3).toNanos();
    private static final DefaultRedisScript<Long> RELEASE_STATE_LOCK_SCRIPT = releaseStateLockScript();
    private static final long MAX_QUEUE_SIZE = 2_500_000;
    public static final Long INACTIVE_CAMPAIGN = -999L;
    public static final Long QUEUE_FULL = -998L;
    public static final Long ALREADY_PARTICIPATED = -997L;


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
        String token = awaitStateLock(campaignId);
        try {
            redisTemplate.opsForSet().add(ACTIVE_CAMPAIGNS_KEY, campaignId.toString());
            redisTemplate.opsForValue().set(getActiveFlagKey(campaignId), "1");
        } finally {
            releaseStateLock(campaignId, token);
        }
    }

    // 캠페인 비활성화 — Bridge 순회용 전역 Set + Lua용 캠페인별 플래그 둘 다 정리
    public void deactivateCampaign(Long campaignId) {
        String token = awaitStateLock(campaignId);
        try {
            redisTemplate.opsForSet().remove(ACTIVE_CAMPAIGNS_KEY, campaignId.toString());
            redisTemplate.delete(getActiveFlagKey(campaignId));
        } finally {
            releaseStateLock(campaignId, token);
        }
    }

    /**
     * Bridge cleanup only. Recovery activation and Set removal share the same per-campaign lock.
     * The active flag is already absent here, so do not delete it after a concurrent recovery.
     * A busy lock is safe to skip: the next Bridge cycle will retry.
     */
    public boolean deactivateIfQueueEmpty(Long campaignId) {
        String token = tryAcquireStateLock(campaignId);
        if (token == null) {
            return false;
        }
        boolean removed = false;
        try {
            String queueKey = getQueueKey(campaignId);
            Long remaining = redisTemplate.opsForList().size(queueKey);
            if (isActive(campaignId) || remaining == null || remaining > 0) {
                return false;
            }

            redisTemplate.opsForSet().remove(ACTIVE_CAMPAIGNS_KEY, campaignId.toString());
            removed = true;

            // Queue writes are atomic within the campaign's Redis Cluster slot, but the
            // global active Set is in another slot. Restore its index if state changed.
            Long afterRemoval = redisTemplate.opsForList().size(queueKey);
            if (isActive(campaignId) || afterRemoval == null || afterRemoval > 0) {
                redisTemplate.opsForSet().add(ACTIVE_CAMPAIGNS_KEY, campaignId.toString());
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            // A failed post-removal read must not leave a known campaign out of the index.
            if (removed) {
                try {
                    redisTemplate.opsForSet().add(ACTIVE_CAMPAIGNS_KEY, campaignId.toString());
                } catch (RuntimeException restoreFailure) {
                    e.addSuppressed(restoreFailure);
                }
            }
            throw e;
        } finally {
            releaseStateLock(campaignId, token);
        }
    }

    // Bridge cleanup 판단용 — active flag 존재 여부 확인
    public boolean isActive(Long campaignId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(getActiveFlagKey(campaignId)));
    }

    /**
     * EXISTS + 큐 만원 체크 + DECR + LPUSH + GET total 원자 실행 (Lua)
     * returns long[]{remaining, total}
     * remaining == INACTIVE_CAMPAIGN(-999): 비활성 캠페인
     * remaining == QUEUE_FULL(-998): 큐 만원 (재고 차감 안 됨)
     * remaining < 0: 재고 소진 (초과 요청, 재고 차감 됨)
     */
    @SuppressWarnings("unchecked")
    public long[] checkDecrEnqueue(Long campaignId, Long userId) {
        List<Long> result = (List<Long>) redisTemplate.execute(
            checkDecrEnqueueScript,
            List.of(getActiveFlagKey(campaignId), getStockKey(campaignId), getTotalKey(campaignId), getQueueKey(campaignId), getParticipatedKey(campaignId, userId)),
            String.valueOf(MAX_QUEUE_SIZE),
            String.valueOf(campaignId),
            String.valueOf(userId)
        );
        if (result == null || result.size() < 2) {
            throw new IllegalStateException("checkDecrEnqueue 스크립트 오류. campaignId=" + campaignId);
        }
        return new long[]{result.get(0), result.get(1)};
    }

    // 캠페인 생성 시 totalStock Redis 저장 (sequence 계산 목적, DB findById 대체)
    public void initializeTotal(Long campaignId, Long totalStock) {
        redisTemplate.opsForValue().set(getTotalKey(campaignId), String.valueOf(totalStock));
    }

    public Long getTotal(Long campaignId) {
        String total = redisTemplate.opsForValue().get(getTotalKey(campaignId));
        return total != null ? Long.parseLong(total) : null;
    }

    public boolean hasTotal(Long campaignId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(getTotalKey(campaignId)));
    }

    public boolean isRegisteredInActiveCampaigns(Long campaignId) {
        return Boolean.TRUE.equals(
                redisTemplate.opsForSet().isMember(ACTIVE_CAMPAIGNS_KEY, campaignId.toString())
        );
    }

    public void deleteTotal(Long campaignId) {
        redisTemplate.delete(getTotalKey(campaignId));
    }

    public void clearRuntimeState(Long campaignId) {
        deleteStock(campaignId);
        deleteTotal(campaignId);
        deactivateCampaign(campaignId);
    }

    private String getQueueKey(Long campaignId) {
        return QUEUE_KEY_PREFIX + campaignId + "}";
    }

    private String getParticipatedKey(Long campaignId, Long userId) {
        return PARTICIPATED_KEY_PREFIX + campaignId + "}:user:" + userId;
    }

    private String getTotalKey(Long campaignId) {
        return TOTAL_KEY_PREFIX + campaignId + "}";
    }

    private String getActiveFlagKey(Long campaignId) {
        return ACTIVE_FLAG_KEY_PREFIX + campaignId + "}";
    }

    private String getStateLockKey(Long campaignId) {
        return STATE_LOCK_KEY_PREFIX + campaignId + "}:state";
    }

    private String tryAcquireStateLock(Long campaignId) {
        String token = UUID.randomUUID().toString();
        return Boolean.TRUE.equals(redisTemplate.opsForValue()
                .setIfAbsent(getStateLockKey(campaignId), token, STATE_LOCK_LEASE)) ? token : null;
    }

    private String awaitStateLock(Long campaignId) {
        long deadline = System.nanoTime() + STATE_LOCK_WAIT_NANOS;
        String token;
        while ((token = tryAcquireStateLock(campaignId)) == null) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("Timed out waiting for campaign state lock. campaignId=" + campaignId);
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for campaign state lock. campaignId=" + campaignId, e);
            }
        }
        return token;
    }

    private void releaseStateLock(Long campaignId, String token) {
        try {
            Long released = redisTemplate.execute(RELEASE_STATE_LOCK_SCRIPT, List.of(getStateLockKey(campaignId)), token);
            if (!Long.valueOf(1L).equals(released)) {
                log.warn("Campaign state lock expired before release. campaignId={}", campaignId);
            }
        } catch (RuntimeException e) {
            // The lease expires even if release fails; do not mask the state transition result.
            log.error("Failed to release campaign state lock. campaignId={}", campaignId, e);
        }
    }

    private static DefaultRedisScript<Long> releaseStateLockScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText("if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end");
        script.setResultType(Long.class);
        return script;
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
