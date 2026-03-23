package io.eventdriven.campaign.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 배치 작업 관련 설정 프로퍼티
 * - application.yml의 batch.* 설정을 주입받음
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "batch")
public class BatchProperties {

    private Aggregation aggregation = new Aggregation();
    private Metadata metadata = new Metadata();

    @Getter
    @Setter
    public static class Aggregation {
        /**
         * 집계 가능한 최대 과거 기간 (년)
         */
        private int maxPastYears = 1;
    }

    @Getter
    @Setter
    public static class Metadata {
        /**
         * 배치 메타데이터 보관 기간 (일)
         */
        private int retentionDays = 90;
    }
}
