package io.eventdriven.campaign.domain.repository;

import io.eventdriven.campaign.domain.entity.ParticipationHistory;
import io.eventdriven.campaign.domain.entity.ParticipationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ParticipationHistoryRepository extends JpaRepository<ParticipationHistory, Long> {

    /**
     * 캠페인별 상태별 건수 조회 (실시간 현황 API용)
     */
    Long countByCampaignIdAndStatus(Long campaignId, ParticipationStatus status);

    /**
     * 캠페인별 성공 건수 조회
     */
    @Query("""
        SELECT COUNT(ph)
        FROM ParticipationHistory ph
        WHERE ph.campaign.id = :campaignId AND ph.status = 'SUCCESS'
    """)
    Long countSuccessByCampaignId(@Param("campaignId") Long campaignId);

    /**
     * 캠페인별 실패 건수 조회
     */
    @Query("""
        SELECT COUNT(ph)
        FROM ParticipationHistory ph
        WHERE ph.campaign.id = :campaignId AND ph.status = 'FAIL'
    """)
    Long countFailByCampaignId(@Param("campaignId") Long campaignId);

    /**
     * 캠페인별 참여 이력 조회 (Kafka 메시지 생성 시간 순서대로, 순서 분석용)
     * kafka_timestamp 순서로 정렬하여 실제 메시지 생성 순서를 확인
     */
    @Query("""
        SELECT ph
        FROM ParticipationHistory ph
        WHERE ph.campaign.id = :campaignId AND ph.kafkaTimestamp IS NOT NULL
        ORDER BY ph.kafkaTimestamp ASC
    """)
    List<ParticipationHistory> findByCampaignIdOrderByKafkaTimestampAsc(@Param("campaignId") Long campaignId);

    /**
     * 캠페인별 최근 참여 이력 조회 (실시간 처리 성능 측정용)
     * 지정된 시간 이후의 데이터를 생성 시간 순서대로 조회
     */
    List<ParticipationHistory> findByCampaignIdAndCreatedAtAfterOrderByCreatedAtAsc(Long campaignId, LocalDateTime createdAt);
}
