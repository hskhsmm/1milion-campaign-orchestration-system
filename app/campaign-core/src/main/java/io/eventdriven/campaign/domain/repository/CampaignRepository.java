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

    @Modifying
    @Query("UPDATE Campaign c SET c.currentStock = c.currentStock - 1 " +
           "WHERE c.id = :id AND c.currentStock > 0")
    int decreaseStockAtomic(@Param("id") Long id);

    // 재고 소진 시 캠페인 종료 — remaining==0 케이스에서 정확히 1번만 호출
    @Transactional
    @Modifying
    @Query("UPDATE Campaign c SET c.status = :status, c.currentStock = 0 WHERE c.id = :id")
    void closeAndResetStock(@Param("id") Long id, @Param("status") CampaignStatus status);

    List<Campaign> findByStatus(CampaignStatus status);
}
