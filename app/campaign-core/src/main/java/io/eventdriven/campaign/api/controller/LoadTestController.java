package io.eventdriven.campaign.api.controller;

import io.eventdriven.campaign.api.common.ApiResponse;
import io.eventdriven.campaign.api.dto.request.LoadTestRequest;
import io.eventdriven.campaign.api.dto.response.LoadTestResult;
import io.eventdriven.campaign.application.service.LoadTestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/admin/load-test")
@RequiredArgsConstructor
public class LoadTestController {

    private final LoadTestService loadTestService;

    /**
     * Kafka 방식 부하 테스트 실행
     * POST /api/admin/load-test/kafka
     */
    @PostMapping("/kafka")
    public ResponseEntity<ApiResponse<Map<String, String>>> executeKafkaTest(
            @RequestBody @Valid LoadTestRequest request
    ) {
        log.info("🚀 Kafka 부하 테스트 요청 - CampaignID: {}, TotalRequests: {}, Partitions: {}",
                request.getCampaignId(), request.getTotalRequests(), request.getPartitions());

        String jobId = loadTestService.executeKafkaTest(request);

        return ResponseEntity.ok(
                ApiResponse.success(
                        "Kafka 부하 테스트가 시작되었습니다.",
                        Map.of("jobId", jobId)
                )
        );
    }

    /**
     * 동기 방식 부하 테스트 실행
     * POST /api/admin/load-test/sync
     */
    @PostMapping("/sync")
    public ResponseEntity<ApiResponse<Map<String, String>>> executeSyncTest(
            @RequestBody @Valid LoadTestRequest request
    ) {
        log.info("🚀 동기 부하 테스트 요청 - CampaignID: {}, TotalRequests: {}, Partitions: {}",
                request.getCampaignId(), request.getTotalRequests(), request.getPartitions());

        String jobId = loadTestService.executeSyncTest(request);

        return ResponseEntity.ok(
                ApiResponse.success(
                        "동기 부하 테스트가 시작되었습니다.",
                        Map.of("jobId", jobId)
                )
        );
    }

    /**
     * 부하 테스트 결과 조회
     * GET /api/admin/load-test/results/{jobId}
     */
    @GetMapping("/results/{jobId}")
    public ResponseEntity<ApiResponse<?>> getTestResult(
            @PathVariable String jobId
    ) {
        LoadTestResult result = loadTestService.getTestResult(jobId);

        if (result == null) {
            return ResponseEntity.ok(
                    ApiResponse.fail("LOAD_TEST_001", "테스트 결과를 찾을 수 없습니다.")
            );
        }

        return ResponseEntity.ok(
                ApiResponse.success(result)
        );
    }
}
