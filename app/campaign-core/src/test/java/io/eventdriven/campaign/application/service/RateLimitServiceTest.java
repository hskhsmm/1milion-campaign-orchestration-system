package io.eventdriven.campaign.application.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitServiceTest {

    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    @InjectMocks private RateLimitService rateLimitService;

    private static final Long CAMPAIGN_ID = 1L;
    private static final Long USER_ID = 42L;
    private static final String EXPECTED_KEY = "ratelimit:campaign:1:user:42";

    @Test
    @DisplayName("첫 요청 → SET NX 성공 → 허용")
    void isAllowed_firstRequest_allowed() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq(EXPECTED_KEY), eq("1"), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);

        assertThat(rateLimitService.isAllowed(CAMPAIGN_ID, USER_ID)).isTrue();
    }

    @Test
    @DisplayName("10초 내 재요청 → 키 이미 존재 → 차단")
    void isAllowed_duplicateWithinTtl_blocked() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq(EXPECTED_KEY), eq("1"), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(false);

        assertThat(rateLimitService.isAllowed(CAMPAIGN_ID, USER_ID)).isFalse();
    }

    @Test
    @DisplayName("Redis null 반환(네트워크 오류) → 차단 (NPE 방지)")
    void isAllowed_nullResponse_blocked() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq(EXPECTED_KEY), eq("1"), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(null);

        assertThat(rateLimitService.isAllowed(CAMPAIGN_ID, USER_ID)).isFalse();
    }

    @Test
    @DisplayName("release → Rate Limit 키 삭제 (Queue full 재시도 허용)")
    void release_deletesRateLimitKey() {
        rateLimitService.release(CAMPAIGN_ID, USER_ID);

        verify(redisTemplate).delete(EXPECTED_KEY);
    }
}
