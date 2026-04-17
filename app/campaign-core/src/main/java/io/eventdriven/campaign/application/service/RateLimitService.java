package io.eventdriven.campaign.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

//롬복의 로그
@Slf4j

@Service
@RequiredArgsConstructor

public class RateLimitService {
 //  RedisTemplate 타입이 필요, redistamplate 의존성 빈을 컨테이너에서 주입을 하게 됨.
 //  스프링 시작이 컨테이너에 등록되어짐.

 // 외부에서 잘못된 키 설정, 삭제, TTL 조정을 막기 위해, 잘못된 주입 방지하기 위해 private final
     private final RedisTemplate<String, String> redisTemplate;

 //키 값 접근 및 잘못된 설정 방지, 같은 상수 값을 사용하기에 static을 사용
     private static final String KEY_PREFIX = "ratelimit:campaign:";
     private static final long TTL_SECONDS = 10;


    public boolean isAllowed(Long campaignId, Long userId) {

        // 유저별 고유 키 생성
        // ex) "rateLimit:campaign:1:user:42"
        // 키의 존재 자체가 "이미 요청했음"의 증거
        String key = KEY_PREFIX + campaignId + ":user:" + userId;

        // Redis SET NX EX
        // 키 없으면  "1" String 타입으로 저장 + TTL 설정 → true (첫 요청, 통과)
        // 키 있으면 아무것도 안함 → false (TTL 안에 재요청, 차단)
        // "1"은 Redis가 빈 값을 허용 안해서 넣는 더미값 (검증에 사용 안함)
        // TTL_SECONDS, TimeUnit.SECONDS는 저장값이 아닌 만료 시간 설정 파라미터
        Boolean result = redisTemplate.opsForValue()
                .setIfAbsent(key, "1", TTL_SECONDS, TimeUnit.SECONDS);

        //Redis 연결 문제나 네트워크 오류 시 null이 반환될 수 있기 때문에
        // Boolean.TRUE.equals()로 NPE 방지
        // true 통과 / false는 차단
        return Boolean.TRUE.equals(result);   // null일 경우 false 반환
    }
}
