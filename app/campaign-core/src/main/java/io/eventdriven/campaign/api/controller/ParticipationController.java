package io.eventdriven.campaign.api.controller;

import io.eventdriven.campaign.api.common.ApiResponse;
import io.eventdriven.campaign.api.dto.request.ParticipationRequest;
import io.eventdriven.campaign.api.exception.business.CampaignNotFoundException;
import io.eventdriven.campaign.application.service.ParticipationService;
import io.eventdriven.campaign.application.service.RedisStockService;
import io.eventdriven.campaign.domain.entity.Campaign;
import io.eventdriven.campaign.domain.entity.ParticipationHistory;
import io.eventdriven.campaign.domain.repository.CampaignRepository;
import io.eventdriven.campaign.domain.repository.ParticipationHistoryRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/campaigns")
@RequiredArgsConstructor
public class ParticipationController {

    private final ParticipationService participationService;
    private final CampaignRepository campaignRepository;
    private final ParticipationHistoryRepository participationHistoryRepository;
    private final RedisStockService redisStockService;

    /**
     * 선착순 참여 요청 (Kafka 방식 - 비동기)
     * POST /api/campaigns/{campaignId}/participation
     */
    @PostMapping("/{campaignId}/participation")
    public ResponseEntity<ApiResponse<Void>> participate(
            @PathVariable Long campaignId,
            @RequestBody @Valid ParticipationRequest request
    ) {
        participationService.participate(campaignId, request.getUserId());
        return ResponseEntity.ok(
                ApiResponse.success("참여 요청이 접수되었습니다.")
        );
    }

    /**
     * 선착순 참여 요청 (동기 방식 - 성능 비교용, 느리고 불안정함!)
     * POST /api/campaigns/{campaignId}/participation-sync
     */
    @PostMapping("/{campaignId}/participation-sync")
    @Transactional
    public ResponseEntity<ApiResponse<?>> participateSync(
            @PathVariable Long campaignId,
            @RequestBody @Valid ParticipationRequest request
    ) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new CampaignNotFoundException(campaignId));

        // Kafka 없이 바로 DB 처리 (동기 - 경합 발생!)
        int updated = campaignRepository.decreaseStockAtomic(campaignId);

        io.eventdriven.campaign.domain.entity.ParticipationStatus status;
        if (updated > 0) {
            status = io.eventdriven.campaign.domain.entity.ParticipationStatus.SUCCESS;
        } else {
            status = io.eventdriven.campaign.domain.entity.ParticipationStatus.FAIL;
        }

        // 이력 저장
        io.eventdriven.campaign.domain.entity.ParticipationHistory history =
                new io.eventdriven.campaign.domain.entity.ParticipationHistory(
                        campaign, request.getUserId(), status
                );
        participationHistoryRepository.save(history);

        Map<String, Object> data = Map.of(
                "status", status.toString(),
                "method", "SYNC"
        );

        return ResponseEntity.ok(
                ApiResponse.success("참여 처리 완료 (동기 방식)", data)
        );
    }

    /**
     * 캠페인 실시간 현황 조회
     * GET /api/campaigns/{id}/status
     */
    @GetMapping("/{id}/status")
    public ResponseEntity<ApiResponse<?>> getCampaignStatus(@PathVariable Long id) {
        Campaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new CampaignNotFoundException(id));

        // 실시간 집계 (participation_history에서)
        Long successCount = participationHistoryRepository.countSuccessByCampaignId(id);
        Long failCount = participationHistoryRepository.countFailByCampaignId(id);

        // null 체크 (데이터가 없을 경우 대비)
        successCount = successCount != null ? successCount : 0L;
        failCount = failCount != null ? failCount : 0L;
        Long totalCount = successCount + failCount;

        // Redis에서 실시간 재고 조회 (없으면 MySQL fallback)
        Long currentStock = redisStockService.getStock(id);
        if (currentStock == null) {
            currentStock = campaign.getCurrentStock();
        }

        Map<String, Object> data = new HashMap<>();
        data.put("campaignId", campaign.getId());
        data.put("campaignName", campaign.getName());
        data.put("totalStock", campaign.getTotalStock());
        data.put("currentStock", currentStock);
        data.put("successCount", successCount);
        data.put("failCount", failCount);
        data.put("totalParticipation", totalCount);

        // 재고 사용률 계산
        double usageRate = campaign.getTotalStock() > 0
            ? (campaign.getTotalStock() - currentStock) * 100.0 / campaign.getTotalStock()
            : 0.0;
        data.put("stockUsageRate", String.format("%.2f%%", usageRate));

        // ===== 실제 처리 성능 지표 추가 (최근 5초) =====
        java.time.LocalDateTime since = java.time.LocalDateTime.now().minusSeconds(5);
        List<ParticipationHistory> recentRecords = participationHistoryRepository
                .findByCampaignIdAndCreatedAtAfterOrderByCreatedAtAsc(id, since);

        if (!recentRecords.isEmpty()) {
            // 1. 실제 처리 TPS (메시지 처리 속도)
            java.time.LocalDateTime firstProcessed = recentRecords.get(0).getCreatedAt();
            java.time.LocalDateTime lastProcessed = recentRecords.get(recentRecords.size() - 1).getCreatedAt();
            long durationSeconds = java.time.Duration.between(firstProcessed, lastProcessed).getSeconds();
            double actualTps = durationSeconds > 0 ? (double) recentRecords.size() / durationSeconds : 0;

            // 2. 평균 처리 지연시간 (Kafka → DB)
            double avgLatencyMs = recentRecords.stream()
                    .filter(r -> r.getKafkaTimestamp() != null)
                    .mapToLong(r -> {
                        java.time.LocalDateTime kafkaTime = java.time.Instant.ofEpochMilli(r.getKafkaTimestamp())
                                .atZone(java.time.ZoneId.of("Asia/Seoul"))
                                .toLocalDateTime();
                        return java.time.Duration.between(kafkaTime, r.getCreatedAt()).toMillis();
                    })
                    .average()
                    .orElse(0.0);

            Map<String, Object> processingMetrics = new HashMap<>();
            processingMetrics.put("actualTps", Math.round(actualTps * 100.0) / 100.0);
            processingMetrics.put("avgLatencyMs", Math.round(avgLatencyMs * 100.0) / 100.0);
            processingMetrics.put("recentProcessed", recentRecords.size());

            data.put("processingMetrics", processingMetrics);
        } else {
            // 최근 데이터 없음
            Map<String, Object> processingMetrics = new HashMap<>();
            processingMetrics.put("actualTps", 0.0);
            processingMetrics.put("avgLatencyMs", 0.0);
            processingMetrics.put("recentProcessed", 0);

            data.put("processingMetrics", processingMetrics);
        }

        return ResponseEntity.ok(ApiResponse.success(data));
    }
}
