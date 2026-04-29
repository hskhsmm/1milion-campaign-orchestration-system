package io.eventdriven.campaign.domain.repository;

import io.eventdriven.campaign.domain.entity.DlqReplayExecutionItem;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DlqReplayExecutionItemRepository extends JpaRepository<DlqReplayExecutionItem, Long> {

    @Query("""
        SELECT i
        FROM DlqReplayExecutionItem i
        WHERE i.execution.id = :executionId
        ORDER BY i.id ASC
    """)
    List<DlqReplayExecutionItem> findByExecutionIdOrderByIdAsc(
            @Param("executionId") Long executionId,
            Pageable pageable
    );
}
