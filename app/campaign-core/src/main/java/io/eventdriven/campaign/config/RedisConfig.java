package io.eventdriven.campaign.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Redis 설정
 * - Lua 스크립트 Bean 등록
 */
@Configuration
public class RedisConfig {

    /**
     * 재고 차감 Lua 스크립트
     * 재고가 0보다 클 때만 차감 (원자적 연산)
     */
    @Bean
    public DefaultRedisScript<Long> decreaseStockScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/decrease-stock.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
