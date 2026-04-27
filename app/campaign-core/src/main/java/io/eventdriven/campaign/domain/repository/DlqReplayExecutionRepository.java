package io.eventdriven.campaign.domain.repository;

import io.eventdriven.campaign.domain.entity.DlqReplayExecution;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DlqReplayExecutionRepository extends JpaRepository<DlqReplayExecution, Long> {
}
