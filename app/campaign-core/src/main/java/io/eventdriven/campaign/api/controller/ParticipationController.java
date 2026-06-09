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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
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

    @PostMapping("/{campaignId}/participation")
    public ResponseEntity<ApiResponse<Void>> participate(
            @PathVariable Long campaignId,
            @RequestBody @Valid ParticipationRequest request
    ) {
        participationService.participate(campaignId, request.getUserId());
        return ResponseEntity.accepted().body(
                ApiResponse.success("Participation request accepted")
        );
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<ApiResponse<?>> getCampaignStatus(@PathVariable Long id) {
        Campaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new CampaignNotFoundException(id));

        Long successCount = nvl(participationHistoryRepository.countSuccessByCampaignId(id));
        Long failCount = nvl(participationHistoryRepository.countFailByCampaignId(id));
        Long totalCount = successCount + failCount;

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
        data.put("stockUsageRate", formatUsageRate(campaign.getTotalStock(), currentStock));
        data.put("processingMetrics", buildProcessingMetrics(id));

        return ResponseEntity.ok(ApiResponse.success(data));
    }

    private Map<String, Object> buildProcessingMetrics(Long campaignId) {
        LocalDateTime since = LocalDateTime.now().minusSeconds(5);
        List<ParticipationHistory> recentRecords = participationHistoryRepository
                .findByCampaignIdAndCreatedAtAfterOrderByCreatedAtAsc(campaignId, since);

        if (recentRecords.isEmpty()) {
            return Map.of(
                    "actualTps", 0.0,
                    "avgLatencyMs", 0.0,
                    "recentProcessed", 0
            );
        }

        LocalDateTime firstProcessed = recentRecords.get(0).getCreatedAt();
        LocalDateTime lastProcessed = recentRecords.get(recentRecords.size() - 1).getCreatedAt();
        long durationSeconds = Duration.between(firstProcessed, lastProcessed).getSeconds();
        double actualTps = durationSeconds > 0 ? (double) recentRecords.size() / durationSeconds : 0;

        double avgLatencyMs = recentRecords.stream()
                .filter(r -> r.getKafkaTimestamp() != null)
                .mapToLong(r -> {
                    LocalDateTime kafkaTime = Instant.ofEpochMilli(r.getKafkaTimestamp())
                            .atZone(ZoneId.of("Asia/Seoul"))
                            .toLocalDateTime();
                    return Duration.between(kafkaTime, r.getCreatedAt()).toMillis();
                })
                .average()
                .orElse(0.0);

        Map<String, Object> metrics = new HashMap<>();
        metrics.put("actualTps", Math.round(actualTps * 100.0) / 100.0);
        metrics.put("avgLatencyMs", Math.round(avgLatencyMs * 100.0) / 100.0);
        metrics.put("recentProcessed", recentRecords.size());
        return metrics;
    }

    private String formatUsageRate(Long totalStock, Long currentStock) {
        double usageRate = totalStock > 0
                ? (totalStock - currentStock) * 100.0 / totalStock
                : 0.0;
        return String.format("%.2f%%", usageRate);
    }

    private Long nvl(Long value) {
        return value != null ? value : 0L;
    }
}
