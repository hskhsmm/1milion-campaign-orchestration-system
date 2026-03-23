package io.eventdriven.campaign.api.controller;

import io.eventdriven.campaign.api.common.ApiResponse;
import io.eventdriven.campaign.application.service.ProcessingLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 관리자 로그 조회 컨트롤러
 */
@RestController
@RequestMapping("/api/admin/logs")
@RequiredArgsConstructor
public class AdminLogController {

    private final ProcessingLogService processingLogService;

    /**
     * 최근 처리 로그 조회
     * GET /api/admin/logs?limit=100
     */
    @GetMapping
    public ResponseEntity<ApiResponse<?>> getRecentLogs(
            @RequestParam(defaultValue = "100") int limit
    ) {
        List<ProcessingLogService.LogEntry> logs = processingLogService.getRecentLogs(limit);
        return ResponseEntity.ok(ApiResponse.success(logs));
    }

    /**
     * 모든 로그 조회
     * GET /api/admin/logs/all
     */
    @GetMapping("/all")
    public ResponseEntity<ApiResponse<?>> getAllLogs() {
        List<ProcessingLogService.LogEntry> logs = processingLogService.getAllLogs();
        return ResponseEntity.ok(ApiResponse.success(logs));
    }

    /**
     * 로그 초기화
     * DELETE /api/admin/logs
     */
    @DeleteMapping
    public ResponseEntity<ApiResponse<?>> clearLogs() {
        processingLogService.clearLogs();
        return ResponseEntity.ok(ApiResponse.success("로그가 초기화되었습니다."));
    }
}
