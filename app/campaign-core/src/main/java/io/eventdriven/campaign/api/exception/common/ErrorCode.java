package io.eventdriven.campaign.api.exception.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 에러 코드 정의
 * - 각 예외에 대한 코드, 메시지, HTTP 상태 정의
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // ===== 비즈니스 예외 (4xx) =====

    // 캠페인 관련
    CAMPAIGN_NOT_FOUND(HttpStatus.NOT_FOUND, "CAMPAIGN_001", "존재하지 않는 캠페인입니다."),
    CAMPAIGN_ALREADY_CLOSED(HttpStatus.BAD_REQUEST, "CAMPAIGN_002", "이미 종료된 캠페인입니다."),

    // 참여 관련
    DUPLICATE_PARTICIPATION(HttpStatus.CONFLICT, "PARTICIPATION_001", "이미 참여한 캠페인입니다."),
    INVALID_USER(HttpStatus.BAD_REQUEST, "PARTICIPATION_002", "유효하지 않은 사용자입니다."),

    // 배치 관련
    BATCH_ALREADY_EXECUTED(HttpStatus.CONFLICT, "BATCH_001", "해당 날짜는 이미 집계되었습니다."),
    INVALID_DATE_RANGE(HttpStatus.BAD_REQUEST, "BATCH_002", "유효하지 않은 날짜 범위입니다."),
    BATCH_JOB_RUNNING(HttpStatus.CONFLICT, "BATCH_003", "배치 작업이 이미 실행 중입니다."),

    // 검증 관련
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "COMMON_001", "잘못된 요청 파라미터입니다."),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "COMMON_002", "입력값 검증에 실패했습니다."),

    // ===== 인프라 예외 (5xx) =====

    // Kafka 관련
    KAFKA_PUBLISH_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "KAFKA_001", "메시지 발행에 실패했습니다."),
    KAFKA_SERIALIZATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "KAFKA_003", "메시지 직렬화에 실패했습니다."),
    KAFKA_DESERIALIZATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "KAFKA_004", "메시지 역직렬화에 실패했습니다."),

    // 데이터베이스 관련
    DATABASE_CONNECTION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "DB_002", "데이터베이스 연결에 실패했습니다."),

    // 일반 서버 오류
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "SERVER_001", "서버 내부 오류가 발생했습니다."),
    EXTERNAL_API_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "SERVER_002", "외부 API 호출에 실패했습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
