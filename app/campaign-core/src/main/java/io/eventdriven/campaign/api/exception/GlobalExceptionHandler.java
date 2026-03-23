package io.eventdriven.campaign.api.exception;

import io.eventdriven.campaign.api.common.ApiResponse;
import io.eventdriven.campaign.api.exception.common.BusinessException;
import io.eventdriven.campaign.api.exception.common.InfrastructureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 전역 예외 처리 핸들러
 * - 모든 컨트롤러의 예외를 일관된 ApiResponse 형식으로 반환
 * - 커스텀 예외 계층 구조를 통한 체계적인 예외 처리
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 비즈니스 예외 처리 (4xx)
     * - 클라이언트 측 오류 (잘못된 요청, 비즈니스 규칙 위반 등)
     * - 에러 코드와 함께 응답
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        log.warn("⚠️ 비즈니스 예외 발생 - {}: {}", e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getHttpStatus())
                .body(ApiResponse.fail(e.getCode(), e.getMessage()));
    }

    /**
     * 인프라 예외 처리 (5xx)
     * - 서버 측 오류 (Kafka, DB, 외부 API 등)
     * - 운영자 알림이 필요한 예외
     */
    @ExceptionHandler(InfrastructureException.class)
    public ResponseEntity<ApiResponse<Void>> handleInfrastructureException(InfrastructureException e) {
        log.error("🚨 인프라 예외 발생 - {}: {}", e.getCode(), e.getMessage(), e);

        // 운영자 알림 필요 (실제 환경에서는 Slack, Email 등으로 전송)
        if (e.requiresAlert()) {
            log.error("🔔 [ALERT] 운영자 개입 필요 - {}: {}", e.getCode(), e.getMessage());
            // TODO: alertService.sendAlert(e);
        }

        return ResponseEntity.status(e.getHttpStatus())
                .body(ApiResponse.fail(e.getCode(), "서버 오류가 발생했습니다. 잠시 후 다시 시도해주세요."));
    }

    /**
     * Validation 에러 처리 (@Valid 실패)
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(
            MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        log.warn("⚠️ 검증 실패: {}", message);
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail("COMMON_002", message));
    }

    /**
     * IllegalArgumentException 처리 (비즈니스 로직 검증 실패)
     * - 레거시 코드 호환성을 위해 유지
     * - 새 코드에서는 BusinessException 사용 권장
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgumentException(
            IllegalArgumentException e) {
        log.warn("⚠️ 잘못된 요청: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail("COMMON_001", e.getMessage()));
    }

    /**
     * RuntimeException 처리
     * - 레거시 코드 호환성을 위해 유지
     * - 새 코드에서는 InfrastructureException 사용 권장
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<Void>> handleRuntimeException(
            RuntimeException e) {
        log.error("🚨 런타임 에러 발생", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail("SERVER_001", "서버 내부 오류가 발생했습니다."));
    }

    /**
     * 예상치 못한 예외 처리
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("🚨 예상치 못한 오류 발생", e);
        log.error("🔔 [ALERT] 예상치 못한 예외 발생 - 즉시 확인 필요", e);
        // TODO: alertService.sendCriticalAlert(e);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail("SERVER_001", "서버 오류가 발생했습니다."));
    }
}
