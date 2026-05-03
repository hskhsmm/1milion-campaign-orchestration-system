package io.eventdriven.campaign.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Slf4j
@Service
public class RedisQueueService {

    private final RedisTemplate<String, String> redisTemplate;
    private static final String QUEUE_KEY_PREFIX = "queue:campaign:{";



    public Long size(Long campaignId){
        String key = QUEUE_KEY_PREFIX + campaignId + "}";
        return redisTemplate.opsForList().size(key);
    }



    public String pop(Long campaignId) {
        String key = QUEUE_KEY_PREFIX + campaignId + "}";
        return redisTemplate.opsForList().rightPop(key);
    }



}
