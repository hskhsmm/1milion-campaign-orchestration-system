package io.eventdriven.campaign.api.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * API 공통 응답 형식
 * - 모든 API 응답을 통일된 구조로 제공
 * - success, message, errorCode, data 필드로 구성
 */
@Getter
@AllArgsConstructor
public class ApiResponse<T> {
    private boolean success;
    private String message;
    private String errorCode;  // 에러 코드 (실패 시에만 사용)
    private T data;

    /**
     * 성공 응답 (데이터 포함)
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, "요청이 성공했습니다.", null, data);
    }

    /**
     * 성공 응답 (메시지 + 데이터)
     */
    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(true, message, null, data);
    }

    /**
     * 성공 응답 (메시지만)
     */
    public static ApiResponse<Void> success(String message) {
        return new ApiResponse<>(true, message, null, null);
    }

    /**
     * 실패 응답
     */
    public static ApiResponse<Void> fail(String message) {
        return new ApiResponse<>(false, message, null, null);
    }

    /**
     * 실패 응답 (에러 코드 포함)
     */
    public static ApiResponse<Void> fail(String errorCode, String message) {
        return new ApiResponse<>(false, message, errorCode, null);
    }

    /**
     * 실패 응답 (데이터 포함)
     */
    public static <T> ApiResponse<T> fail(String message, T data) {
        return new ApiResponse<>(false, message, null, data);
    }

    /**
     * 실패 응답 (에러 코드 + 데이터 포함)
     */
    public static <T> ApiResponse<T> fail(String errorCode, String message, T data) {
        return new ApiResponse<>(false, message, errorCode, data);
    }
}
