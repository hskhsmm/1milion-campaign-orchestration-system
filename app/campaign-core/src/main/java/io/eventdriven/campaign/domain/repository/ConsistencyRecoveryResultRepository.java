package io.eventdriven.campaign.domain.repository;

import io.eventdriven.campaign.domain.entity.ConsistencyRecoveryResult;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ConsistencyRecoveryResultRepository extends JpaRepository<ConsistencyRecoveryResult, Long> {

    @Query("""
        SELECT r
        FROM ConsistencyRecoveryResult r
        WHERE r.execution.id = :executionId
        ORDER BY r.id ASC
    """)
    List<ConsistencyRecoveryResult> findByExecutionIdOrderByIdAsc(
            @Param("executionId") Long executionId,
            Pageable pageable
    );
}
