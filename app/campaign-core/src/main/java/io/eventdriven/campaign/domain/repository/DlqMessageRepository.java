package io.eventdriven.campaign.domain.repository;

import io.eventdriven.campaign.domain.entity.DlqMessageProcessingStatus;
import io.eventdriven.campaign.domain.entity.DlqMessageRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface DlqMessageRepository extends JpaRepository<DlqMessageRecord, Long> {
    boolean existsByOriginalTopicAndOriginalPartitionAndOriginalOffset(String topic, Integer partition, Long offset);


    @Query("""
        SELECT COUNT(d)
        FROM DlqMessageRecord d
        WHERE d.processingStatus = :status
          AND (:campaignId IS NULL OR d.campaignId = :campaignId)
          AND (:reason IS NULL OR d.errorReason = :reason)
          AND (:fromTime IS NULL OR d.createdAt >= :fromTime)
          AND (:toTime IS NULL OR d.createdAt <= :toTime)
    """)
    long countReplayCandidates(
            @Param("status") DlqMessageProcessingStatus status,
            @Param("campaignId") Long campaignId,
            @Param("reason") String reason,
            @Param("fromTime") LocalDateTime fromTime,
            @Param("toTime") LocalDateTime toTime
    );

    @Query("""
        SELECT d
        FROM DlqMessageRecord d
        WHERE d.processingStatus = :status
          AND d.id > :afterId
          AND (:campaignId IS NULL OR d.campaignId = :campaignId)
          AND (:reason IS NULL OR d.errorReason = :reason)
          AND (:fromTime IS NULL OR d.createdAt >= :fromTime)
          AND (:toTime IS NULL OR d.createdAt <= :toTime)
        ORDER BY d.id ASC
    """)
    List<DlqMessageRecord> findReplayCandidates(
            @Param("status") DlqMessageProcessingStatus status,
            @Param("afterId") Long afterId,
            @Param("campaignId") Long campaignId,
            @Param("reason") String reason,
            @Param("fromTime") LocalDateTime fromTime,
            @Param("toTime") LocalDateTime toTime,
            Pageable pageable
    );

    @Query("""
        SELECT d
        FROM DlqMessageRecord d
        WHERE (:status IS NULL OR d.processingStatus = :status)
          AND (:campaignId IS NULL OR d.campaignId = :campaignId)
          AND (:reason IS NULL OR d.errorReason = :reason)
        ORDER BY d.id DESC
    """)
    List<DlqMessageRecord> searchMessages(
            @Param("status") DlqMessageProcessingStatus status,
            @Param("campaignId") Long campaignId,
            @Param("reason") String reason,
            Pageable pageable
    );
    @Query("""
        SELECT d.id FROM DlqMessageRecord d
        WHERE d.processingStatus = :status AND d.id > :afterId
          AND (:campaignId IS NULL OR d.campaignId = :campaignId)
          AND (:reason IS NULL OR d.errorReason = :reason)
          AND (:fromTime IS NULL OR d.createdAt >= :fromTime)
          AND (:toTime IS NULL OR d.createdAt <= :toTime)
        ORDER BY d.id ASC
    """)
    List<Long> findReplayCandidateIds(@Param("status") DlqMessageProcessingStatus status,
        @Param("afterId") long afterId, @Param("campaignId") Long campaignId,
        @Param("reason") String reason, @Param("fromTime") LocalDateTime fromTime,
        @Param("toTime") LocalDateTime toTime, Pageable pageable);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select e from DlqMessageRecord e where e.id = :id")
    java.util.Optional<DlqMessageRecord> findLockedById(@org.springframework.data.repository.query.Param("id") Long id);

}
