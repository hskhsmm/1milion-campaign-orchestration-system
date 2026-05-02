package io.eventdriven.campaign.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "batch")
public class BatchProperties {

    private Aggregation aggregation = new Aggregation();
    private Metadata metadata = new Metadata();
    private Replay replay = new Replay();
    private Consistency consistency = new Consistency();

    @Getter
    @Setter
    public static class Aggregation {
        private int maxPastYears = 1;
    }

    @Getter
    @Setter
    public static class Metadata {
        private int retentionDays = 90;
    }

    @Getter
    @Setter
    public static class Replay {
        private int pageSize = 100;
        private int defaultMaxItems = 100;
    }

    @Getter
    @Setter
    public static class Consistency {
        private int pageSize = 100;
        private int defaultMaxCampaigns = 100;
    }
}
