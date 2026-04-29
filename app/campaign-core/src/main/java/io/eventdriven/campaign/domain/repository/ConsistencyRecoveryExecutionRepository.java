package io.eventdriven.campaign.domain.repository;

import io.eventdriven.campaign.domain.entity.ConsistencyRecoveryExecution;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConsistencyRecoveryExecutionRepository extends JpaRepository<ConsistencyRecoveryExecution, Long> {

    List<ConsistencyRecoveryExecution> findByOrderByIdDesc(Pageable pageable);
}
