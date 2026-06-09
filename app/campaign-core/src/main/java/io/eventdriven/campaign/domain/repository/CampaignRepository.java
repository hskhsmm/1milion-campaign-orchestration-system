package io.eventdriven.campaign.domain.repository;

import io.eventdriven.campaign.domain.entity.Campaign;
import io.eventdriven.campaign.domain.entity.CampaignStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {

    @Transactional
    @Modifying
    @Query("UPDATE Campaign c SET c.status = :status, c.currentStock = 0 WHERE c.id = :id")
    void closeAndResetStock(@Param("id") Long id, @Param("status") CampaignStatus status);

    List<Campaign> findByStatus(CampaignStatus status);
}
