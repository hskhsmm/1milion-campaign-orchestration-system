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
     * Consumer 배치 처리 후 한 번에 SUCCESS 업데이트
     * ex) WHERE id IN (1, 2, 3, ... 500) → DB 1번 왕복
     */
    @Modifying
    @Transactional
    @Query("UPDATE ParticipationHistory ph SET ph.status = 'SUCCESS' WHERE ph.id IN :historyIds")
    int bulkUpdateSuccess(@Param("historyIds") List<Long> historyIds);

    /**
     * Consumer 배치 처리 실패 시 한 번에 FAIL 업데이트
     * PENDING 상태로 방치 시 Spring Batch 재처리 대상이 되므로 명시적으로 마킹
     */
    @Modifying
    @Transactional
    @Query("UPDATE ParticipationHistory ph SET ph.status = 'FAIL' WHERE ph.id IN :historyIds")
    int bulkUpdateFail(@Param("historyIds") List<Long> historyIds);

    /**
     * PollingController DB fallback 및 중복 참여 확인용
     */
    Optional<ParticipationHistory> findByCampaignIdAndUserId(Long campaignId, Long userId);

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
