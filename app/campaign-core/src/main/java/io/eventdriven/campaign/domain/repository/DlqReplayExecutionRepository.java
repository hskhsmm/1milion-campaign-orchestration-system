package io.eventdriven.campaign.domain.repository;

import io.eventdriven.campaign.domain.entity.DlqReplayExecution;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DlqReplayExecutionRepository extends JpaRepository<DlqReplayExecution, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select e from DlqReplayExecution e where e.id = :id")
    java.util.Optional<DlqReplayExecution> findLockedById(@org.springframework.data.repository.query.Param("id") Long id);

}
