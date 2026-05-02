package io.eventdriven.campaign.domain.repository;

import io.eventdriven.campaign.domain.entity.ParticipationHistory;
import io.eventdriven.campaign.domain.entity.ParticipationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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
     * v3 — Consumer 직접 INSERT SUCCESS (PENDING 없음)
     * UNIQUE(campaign_id, user_id) 중복 시 무시 → 멱등성 보장
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT IGNORE INTO participation_history
            (campaign_id, user_id, sequence, status, created_at)
        VALUES (:campaignId, :userId, :sequence, 'SUCCESS', NOW())
        """, nativeQuery = true)
    void insertSuccess(
        @Param("campaignId") Long campaignId,
        @Param("userId")     Long userId,
        @Param("sequence")   Long sequence
    );

    /**
     * PollingController DB fallback 및 중복 참여 확인용
     */
    Optional<ParticipationHistory> findByCampaignIdAndUserId(Long campaignId, Long userId);

    @Query("""
        SELECT COUNT(ph) > 0
        FROM ParticipationHistory ph
        WHERE ph.campaign.id = :campaignId
          AND ph.userId = :userId
          AND ph.status = :status
    """)
    boolean existsSuccessHistory(
            @Param("campaignId") Long campaignId,
            @Param("userId") Long userId,
            @Param("status") ParticipationStatus status
    );

    // 5분 초과 PENDING 재처리 배치용
    List<ParticipationHistory> findByStatusAndCreatedAtBefore(ParticipationStatus status, LocalDateTime cutoff);

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
