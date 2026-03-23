package io.eventdriven.campaign.domain.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Table(
    name = "campaign_stats",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_campaign_stats_date",
            columnNames = {"campaign_id", "stats_date"}
        )
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CampaignStats {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id", nullable = false)
    private Campaign campaign;

    @Column(name = "success_count", nullable = false)
    private Long successCount;

    @Column(name = "fail_count", nullable = false)
    private Long failCount;

    @Column(name = "stats_date", nullable = false)
    private LocalDate statsDate;

    public CampaignStats(Campaign campaign, Long successCount, Long failCount, LocalDate statsDate) {
        this.campaign = campaign;
        this.successCount = successCount;
        this.failCount = failCount;
        this.statsDate = statsDate;
    }
}
