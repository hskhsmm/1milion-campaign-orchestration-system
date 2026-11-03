package io.eventdriven.campaign.config;

import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 저장소의 application.yml을 그대로 읽어, Redis Cluster failover 후 토폴로지를 다시 읽도록
 * Lettuce 클라이언트가 구성되는지 확인한다. 연결은 만들지 않는다.
 */
class RedisClusterTopologyRefreshConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class))
            .withPropertyValues("spring.data.redis.cluster.nodes=redis-1:7001,redis-2:7002,redis-3:7003");

    @Test
    void clusterClientRefreshesTopologyAdaptivelyAndPeriodically() {
        runner.run(context -> {
            LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
            var clientConfig = factory.getClientConfiguration();

            assertThat(clientConfig.getCommandTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(clientConfig.getClientOptions()).get().isInstanceOf(ClusterClientOptions.class);
            ClusterTopologyRefreshOptions refresh =
                    ((ClusterClientOptions) clientConfig.getClientOptions().orElseThrow()).getTopologyRefreshOptions();

            assertThat(refresh.getAdaptiveRefreshTriggers()).isNotEmpty();
            assertThat(refresh.isPeriodicRefreshEnabled()).isTrue();
            assertThat(refresh.getRefreshPeriod()).isEqualTo(Duration.ofSeconds(30));
            assertThat(refresh.useDynamicRefreshSources()).isTrue();
        });
    }
}
