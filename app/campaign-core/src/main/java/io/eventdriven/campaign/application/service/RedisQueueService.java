package io.eventdriven.campaign.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;

@RequiredArgsConstructor
@Slf4j
@Service
public class RedisQueueService {

    private final RedisTemplate<String, String> redisTemplate;
    private static final String QUEUE_KEY_PREFIX = "queue:campaign:";
    private static final long MAX_QUEUE_SIZE = 500_000;
    private final DefaultRedisScript<Long> pushQueueScript;





    /**
     *  historyId = PK
     *  Consumer가 historyId로 조회:
     *
     *   SELECT * FROM participation_history WHERE id = 101868
     *   -- PK 조회라 인덱스 타서 O(1)
     *
     SELECT * FROM participation_history
     WHERE campaign_id = 1 AND user_id = 42
     -- 인덱스 없으면 풀스캔

     * @param campaignId
     * @return
     */
    public boolean push(Long campaignId, String message) {
        String key = QUEUE_KEY_PREFIX + campaignId;
        Long result = redisTemplate.execute(
                pushQueueScript,
                Collections.singletonList(key),  // 루아는 키 값을 반드시 배열(리스트) 형태로 받음
                String.valueOf(MAX_QUEUE_SIZE), //가변인자도 스프링이 자동으로 배열로 만들어 전달.
                message
        );
        return Long.valueOf(1).equals(result);
    }




    public Long size(Long campaignId){
        String key = QUEUE_KEY_PREFIX + campaignId;
        return redisTemplate.opsForList().size(key);
    }



    public String pop(Long campaignId) {
        String key = QUEUE_KEY_PREFIX + campaignId;
        return redisTemplate.opsForList().rightPop(key);
    }



}
