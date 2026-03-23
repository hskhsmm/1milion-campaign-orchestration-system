package io.eventdriven.campaign.domain.repository;

import io.eventdriven.campaign.domain.entity.Campaign;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {

    /**
     * 원자적 재고 차감
     *
     * WHERE 조건으로 재고 검증 + SET으로 차감을 하나의 SQL로 실행
     *
     * @param id 캠페인 ID
     * @return 업데이트된 행의 개수 (0: 재고 부족, 1: 성공)
     */
    @Modifying
    @Query("UPDATE Campaign c SET c.currentStock = c.currentStock - 1 " +
           "WHERE c.id = :id AND c.currentStock > 0")
    int decreaseStockAtomic(@Param("id") Long id);
}
