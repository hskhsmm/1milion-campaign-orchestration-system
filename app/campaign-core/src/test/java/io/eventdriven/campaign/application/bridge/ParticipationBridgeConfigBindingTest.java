package io.eventdriven.campaign.application.bridge;

import io.eventdriven.campaign.application.service.RedisStockService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Spring이 설정값을 받는 생성자를 고르고, 잘못된 bridge.claim-batch-size에서 기동이 실패하는지 확인한다. */
class ParticipationBridgeConfigBindingTest {

    @SuppressWarnings("unchecked")
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(RedisTemplate.class, () -> mock(RedisTemplate.class))
            .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
            .withBean(BridgeQueueService.class, () -> mock(BridgeQueueService.class))
            .withBean("bridgeAckExecutor", Executor.class, () -> Runnable::run)
            .withBean(RedisStockService.class, () -> mock(RedisStockService.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(JsonMapper.class, () -> mock(JsonMapper.class))
            .withBean(ParticipationBridge.class);

    @Test
    void defaultsToSingleClaimPath() {
        runner.run(context -> assertThat(batchSize(context.getBean(ParticipationBridge.class))).isEqualTo(1));
    }

    @Test
    void bindsConfiguredBatchSize() {
        runner.withPropertyValues("bridge.claim-batch-size=10")
                .run(context -> assertThat(batchSize(context.getBean(ParticipationBridge.class))).isEqualTo(10));
    }

    /** 배포는 SSM 값을 env 파일로 넣는다. 운영에서 쓰는 env 이름이 실제로 바인딩되는지 확인한다. */
    @Test
    void bindsFromEnvironmentVariable() {
        for (String name : new String[] {"BRIDGE_CLAIM_BATCH_SIZE", "BRIDGE_CLAIMBATCHSIZE"}) {
            runner.withInitializer(ctx -> {
                        ConfigurableEnvironment env = ctx.getEnvironment();
                        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of(name, "10")));
                        ConfigurationPropertySources.attach(env);
                    })
                    .run(context -> assertThat(batchSize(context.getBean(ParticipationBridge.class))).as(name).isEqualTo(10));
        }
    }

    @Test
    void invalidValuesFailStartup() {
        for (String value : new String[] {"0", "-1", "51", "100", "ten"}) {
            runner.withPropertyValues("bridge.claim-batch-size=" + value)
                    .run(context -> assertThat(context).as(value).hasFailed());
        }
    }

    private static Object batchSize(ParticipationBridge bridge) {
        return ReflectionTestUtils.getField(bridge, "claimBatchSize");
    }
}
