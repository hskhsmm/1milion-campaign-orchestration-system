package io.eventdriven.campaign.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Locale;

/** 같은 이미지에서 HTTP 접수와 백그라운드 처리를 나누는 실행 역할. */
public enum CampaignRole {
    COMBINED, API, WORKER;

    private static CampaignRole configuredRole(ConditionContext context) {
        String value = context.getEnvironment().getProperty("campaign.role", "combined");
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("campaign.role must be combined, api, or worker", e);
        }
    }

    public static final class HttpApi implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return configuredRole(context) != WORKER;
        }
    }

    public static final class BackgroundWork implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return configuredRole(context) != API;
        }
    }

    public static final class ScheduledBatch implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            CampaignRole role = configuredRole(context);
            // 분리 모드에서 워커 증설이 같은 정기 Job을 중복 실행하지 않도록 기본값은 꺼 둔다.
            return role != API && context.getEnvironment().getProperty(
                    "campaign.scheduled-batch-enabled", Boolean.class, role == COMBINED);
        }
    }
}
