package io.eventdriven.campaign.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;


/**
 * Redis 설정
 * - Lua 스크립트 Bean 등록
 */
@Configuration
public class RedisConfig {


    @Bean
    public DefaultRedisScript<Long> pushQueueScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/push-queue.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @SuppressWarnings("rawtypes")
    @Bean
    public DefaultRedisScript<List> checkDecrTotalScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/check-decr-total.lua"));
        script.setResultType(List.class);
        return script;
    }


}
