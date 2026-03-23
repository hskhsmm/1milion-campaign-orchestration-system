package io.eventdriven.campaign.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * API 성능 측정 AOP
 * - 모든 RestController의 메서드 실행 시간 자동 측정
 * - 성능 비교 및 병목 구간 분석에 활용
 */
@Slf4j
@Aspect
@Component
public class PerformanceMonitoringAspect {

    /**
     * RestController의 모든 public 메서드 성능 측정
     * - 정상 실행: 메서드명 + 실행시간 로깅
     * - 예외 발생: 메서드명 + 실행시간 + 예외 타입 로깅
     */
    @Around("within(@org.springframework.web.bind.annotation.RestController *)")
    public Object measureApiPerformance(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.nanoTime();
        String methodName = joinPoint.getSignature().toShortString();

        try {
            Object result = joinPoint.proceed();
            long duration = (System.nanoTime() - startTime) / 1_000_000; // 나노초 → 밀리초 변환

            log.info("⏱️ API 성능: {} - {}ms", methodName, duration);
            return result;

        } catch (Exception e) {
            long duration = (System.nanoTime() - startTime) / 1_000_000;

            log.error("⏱️ API 실패: {} - {}ms - 예외: {}",
                    methodName, duration, e.getClass().getSimpleName());
            throw e;
        }
    }
}
