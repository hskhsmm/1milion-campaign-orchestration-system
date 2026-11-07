package io.eventdriven.campaign.config;

import io.eventdriven.campaign.api.controller.AdminController;
import io.eventdriven.campaign.api.controller.BatchController;
import io.eventdriven.campaign.api.controller.ConsistencyRecoveryController;
import io.eventdriven.campaign.api.controller.DlqReplayController;
import io.eventdriven.campaign.api.controller.ParticipationController;
import io.eventdriven.campaign.application.bridge.BridgeQueueService;
import io.eventdriven.campaign.application.bridge.ParticipationBridge;
import io.eventdriven.campaign.application.consumer.ParticipationEventConsumer;
import io.eventdriven.campaign.application.scheduler.QueueMetricsScheduler;
import io.eventdriven.campaign.application.service.CampaignService;
import io.eventdriven.campaign.application.service.ConsistencyRecoveryService;
import io.eventdriven.campaign.application.service.DlqMessageService;
import io.eventdriven.campaign.application.service.DlqReplayService;
import io.eventdriven.campaign.application.service.DlqReplayVerificationService;
import io.eventdriven.campaign.application.service.ParticipationService;
import io.eventdriven.campaign.application.service.RedisStockService;
import io.eventdriven.campaign.application.service.SlackNotificationService;
import io.eventdriven.campaign.batch.BatchScheduler;
import io.eventdriven.campaign.domain.repository.CampaignRepository;
import io.eventdriven.campaign.domain.repository.CampaignStatsRepository;
import io.eventdriven.campaign.domain.repository.ParticipationHistoryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartitionInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.explore.JobExplorer;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class CampaignRoleTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(WebConfig.class, AdminController.class, ParticipationController.class,
                    BatchController.class, DlqReplayController.class, ConsistencyRecoveryController.class,
                    ParticipationBridge.class, ParticipationEventConsumer.class, QueueMetricsScheduler.class,
                    BatchScheduler.class, BridgeAckExecutorConfig.class, KafkaConfig.class)
            .withPropertyValues("spring.kafka.bootstrap-servers=localhost:9092")
            .withBean(CampaignService.class, () -> mock(CampaignService.class))
            .withBean(ParticipationService.class, () -> mock(ParticipationService.class))
            .withBean(CampaignRepository.class, () -> mock(CampaignRepository.class))
            .withBean(ParticipationHistoryRepository.class, () -> mock(ParticipationHistoryRepository.class))
            .withBean(CampaignStatsRepository.class, () -> mock(CampaignStatsRepository.class))
            .withBean(ConsistencyRecoveryService.class, () -> mock(ConsistencyRecoveryService.class))
            .withBean(DlqReplayService.class, () -> mock(DlqReplayService.class))
            .withBean(DlqReplayVerificationService.class, () -> mock(DlqReplayVerificationService.class))
            .withBean(SlackNotificationService.class, () -> mock(SlackNotificationService.class))
            .withBean(DlqMessageService.class, () -> mock(DlqMessageService.class))
            .withBean(RedisStockService.class, () -> mock(RedisStockService.class))
            .withBean(BridgeQueueService.class, () -> mock(BridgeQueueService.class))
            .withBean(RedisTemplate.class, () -> mock(RedisTemplate.class))
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(JsonMapper.class, () -> mock(JsonMapper.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(BatchProperties.class, () -> mock(BatchProperties.class))
            .withBean(JobExplorer.class, () -> mock(JobExplorer.class))
            .withBean("asyncJobLauncher", JobLauncher.class, () -> mock(JobLauncher.class))
            .withBean("aggregateParticipationJob", Job.class, () -> mock(Job.class))
            .withBean("batchMetadataCleanupJob", Job.class, () -> mock(Job.class))
            .withBean("consistencyRecoveryJob", Job.class, () -> mock(Job.class))
            .withBean("dlqReplayJob", Job.class, () -> mock(Job.class))
            .withInitializer(context -> context.addBeanFactoryPostProcessor(factory ->
                    factory.addBeanPostProcessor(new BeanPostProcessor() {
                        @Override
                        public Object postProcessAfterInitialization(Object bean, String name) {
                            if (bean instanceof ConcurrentKafkaListenerContainerFactory<?, ?> listenerFactory) {
                                listenerFactory.setAutoStartup(false);
                            }
                            return bean;
                        }
                    })));

    @ParameterizedTest
    @CsvSource({
            "combined, true, true, true",
            "api, true, false, false",
            "worker, false, true, false"
    })
    void registersHttpAndWorkerComponentsForEachRole(
            String role, boolean http, boolean background, boolean scheduledBatch) {
        assertRole(runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of("CAMPAIGN_ROLE", role)))), http, background, scheduledBatch);
    }

    @Test
    void defaultsToCombined() {
        assertRole(runner, true, true, true);
    }

    @Test
    void permitsScheduledBatchOnlyOnASelectedWorker() {
        assertRole(runner.withPropertyValues("campaign.role=worker", "campaign.scheduled-batch-enabled=true"),
                false, true, true);
        assertRole(runner.withPropertyValues("campaign.role=api", "campaign.scheduled-batch-enabled=true"),
                true, false, false);
        assertRole(runner.withPropertyValues("campaign.role=combined", "campaign.scheduled-batch-enabled=false"),
                true, true, false);
    }

    @Test
    void rejectsAnUnknownRole() {
        runner.withPropertyValues("campaign.role=ap1").run(context ->
                assertThat(context.getStartupFailure()).hasStackTraceContaining(
                        "campaign.role must be combined, api, or worker"));
    }

    private void assertRole(WebApplicationContextRunner configured, boolean http,
                            boolean background, boolean scheduledBatch) {
        try (var kafkaAdmin = mockStatic(AdminClient.class)) {
            if (background) {
                AdminClient admin = mock(AdminClient.class);
                DescribeTopicsResult result = mock(DescribeTopicsResult.class);
                TopicDescription topic = mock(TopicDescription.class);
                when(admin.describeTopics(List.of(KafkaConfig.TOPIC_NAME))).thenReturn(result);
                when(result.allTopicNames()).thenReturn(KafkaFuture.completedFuture(
                        Map.of(KafkaConfig.TOPIC_NAME, topic)));
                when(topic.partitions()).thenReturn(List.of(mock(TopicPartitionInfo.class)));
                kafkaAdmin.when(() -> AdminClient.create(anyMap())).thenReturn(admin);
            }

            configured.run(context -> {
                assertThat(context).hasNotFailed();
                Set<String> routes = registeredRoutes(context);
                Set<String> expectedRoutes = Set.of("/api/campaigns/{campaignId}/participation",
                        "/api/admin/campaigns", "/api/admin/batch/aggregate",
                        "/api/admin/dlq-replay", "/api/admin/consistency-recovery");
                if (http) {
                    assertThat(routes).containsAll(expectedRoutes);
                } else {
                    assertThat(routes).doesNotContainAnyElementsOf(expectedRoutes);
                }

                assertThat(context.containsBean("participationBridge")).isEqualTo(background);
                assertThat(context.containsBean("participationEventConsumer")).isEqualTo(background);
                assertThat(context).hasSingleBean(QueueMetricsScheduler.class);
                assertThat(context.containsBean("bridgeAckExecutor")).isEqualTo(background);
                assertThat(context.containsBean("consumerFactory")).isEqualTo(background);
                assertThat(context.containsBean("kafkaListenerContainerFactory")).isEqualTo(background);
                assertThat(context.containsBean("batchScheduler")).isEqualTo(scheduledBatch);
                assertThat(context.getBean(KafkaListenerEndpointRegistry.class).getListenerContainers())
                        .hasSize(background ? 1 : 0);
            });

            if (!background) {
                kafkaAdmin.verifyNoInteractions();
            }
        }
    }

    private Set<String> registeredRoutes(AssertableWebApplicationContext context) {
        return context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class)
                .getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPatternValues().stream())
                .collect(Collectors.toSet());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class WebConfig {}
}
