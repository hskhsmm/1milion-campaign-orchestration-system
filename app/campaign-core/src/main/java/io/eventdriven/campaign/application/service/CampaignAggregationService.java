package io.eventdriven.campaign.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 캠페인 집계 서비스
 * - GROUP BY를 활용한 단일 쿼리로 모든 캠페인 일괄 집계 (N+1 문제 해결)
 * - 멱등성 보장: ON DUPLICATE KEY UPDATE 사용
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignAggregationService {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * 모든 캠페인 일괄 집계 (단일 쿼리)
     * - GROUP BY로 모든 캠페인 통계를 한 번에 계산
     * - N+1 문제 해결: 캠페인 1000개여도 쿼리 1번
     * - 멱등성 보장: ON DUPLICATE KEY UPDATE 사용
     *
     * @param start 집계 시작 시간
     * @param end 집계 종료 시간
     * @return 업데이트된 행 수
     */
    @Transactional(timeout = 60)
    public int aggregateAllCampaigns(LocalDateTime start, LocalDateTime end) {
        String sql = """
                INSERT INTO campaign_stats (campaign_id, success_count, fail_count, stats_date)
                SELECT p.campaign_id,
                       SUM(CASE WHEN p.status = 'SUCCESS' THEN 1 ELSE 0 END) AS success_count,
                       SUM(CASE WHEN p.status = 'FAIL' THEN 1 ELSE 0 END)    AS fail_count,
                       DATE(:start)                                          AS stats_date
                FROM participation_history p
                WHERE p.created_at >= :start
                  AND p.created_at < :end
                GROUP BY p.campaign_id
                ON DUPLICATE KEY UPDATE
                  success_count = VALUES(success_count),
                  fail_count    = VALUES(fail_count)
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("start", start)
                .addValue("end", end);

        int updated = jdbcTemplate.update(sql, params);

        log.info("✅ 전체 캠페인 일괄 집계 완료 - {} 행 업데이트", updated);

        return updated;
    }
}
