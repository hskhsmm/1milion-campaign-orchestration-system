package io.eventdriven.campaign.domain.repository;

import io.eventdriven.campaign.domain.entity.CampaignStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface CampaignStatsRepository extends JpaRepository<CampaignStats, Long> {

    /**
     * 특정 날짜의 모든 캠페인 통계 조회 (Campaign JOIN FETCH로 N+1 방지)
     */
    @Query("SELECT cs FROM CampaignStats cs JOIN FETCH cs.campaign WHERE cs.statsDate = :statsDate")
    List<CampaignStats> findByStatsDate(@Param("statsDate") LocalDate statsDate);

    /**
     * 특정 캠페인의 기간별 통계 조회 (Campaign JOIN FETCH로 N+1 방지)
     */
    @Query("SELECT cs FROM CampaignStats cs JOIN FETCH cs.campaign " +
           "WHERE cs.campaign.id = :campaignId " +
           "AND cs.statsDate BETWEEN :startDate AND :endDate " +
           "ORDER BY cs.statsDate ASC")
    List<CampaignStats> findByCampaignIdAndStatsDateBetween(
            @Param("campaignId") Long campaignId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    /**
     * 특정 기간의 모든 통계 조회 (Campaign JOIN FETCH로 N+1 방지)
     */
    @Query("SELECT cs FROM CampaignStats cs JOIN FETCH cs.campaign " +
           "WHERE cs.statsDate BETWEEN :startDate AND :endDate " +
           "ORDER BY cs.statsDate ASC, cs.campaign.id ASC")
    List<CampaignStats> findByStatsDateBetween(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    /**
     * 특정 날짜에 집계 데이터가 있는지 확인
     * - 배치 중복 실행 방지용
     */
    boolean existsByStatsDate(LocalDate statsDate);
}
