package io.eventdriven.campaign.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@EnableKafka
@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    // Producer 설정값 (프로필별로 변경 가능)
    @Value("${kafka.producer.buffer-memory:134217728}")  // 기본 128MB
    private long producerBufferMemory;

    @Value("${kafka.producer.batch-size:65536}")  // 기본 64KB
    private int producerBatchSize;

    @Value("${kafka.producer.linger-ms:20}")  // 기본 20ms
    private int producerLingerMs;

    @Value("${kafka.producer.compression-type:none}")  // 기본 none
    private String producerCompressionType;

    @Value("${kafka.producer.max-block-ms:60000}")  // 기본 60초
    private long producerMaxBlockMs;

    // Consumer 설정값 (프로필별로 변경 가능)
    @Value("${kafka.consumer.max-poll-records:500}")  // 기본 500건
    private int consumerMaxPollRecords;

    @Value("${kafka.consumer.max-poll-interval-ms:600000}")  // 기본 10분
    private int consumerMaxPollIntervalMs;

    @Value("${kafka.consumer.session-timeout-ms:45000}")  // 기본 45초
    private int consumerSessionTimeoutMs;

    public static final String TOPIC_NAME = "campaign-participation-topic";

    /**
     * Kafka Producer 설정
     * - Key: String (Campaign ID 등)
     * - Value: String (JSON 문자열)
     * - 신뢰성 설정 추가 (acks=all, idempotence=true)
     */
    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        // 신뢰성 및 순서 보장을 위한 설정
        configProps.put(ProducerConfig.ACKS_CONFIG, "all"); // 모든 리플리카 승인 대기
        configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true); // 멱등성 보장 (중복 방지)
        configProps.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE); // 실패 시 무한 재시도

        // 성능 최적화 설정 (프로필별로 조정 가능)
        configProps.put(ProducerConfig.BUFFER_MEMORY_CONFIG, producerBufferMemory);
        configProps.put(ProducerConfig.BATCH_SIZE_CONFIG, producerBatchSize);
        configProps.put(ProducerConfig.LINGER_MS_CONFIG, producerLingerMs);
        configProps.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, producerCompressionType);
        configProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, producerMaxBlockMs);

        log.info("🔧 Producer 설정 - Buffer: {}MB, Batch: {}KB, Linger: {}ms, Compression: {}, MaxBlock: {}ms",
                producerBufferMemory / 1024 / 1024, producerBatchSize / 1024, producerLingerMs,
                producerCompressionType, producerMaxBlockMs);

        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    /**
     * Kafka Consumer 설정
     * - Key: String
     * - Value: String (JSON 문자열)
     * - 순서 보장을 위해 단일 Consumer로 설정
     */
    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ConsumerConfig.GROUP_ID_CONFIG, "campaign-participation-group");
        configProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        // 성능 최적화를 위해 한 번에 여러 레코드를 가져오도록 설정 (프로필별로 조정 가능)
        configProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, consumerMaxPollRecords);
        configProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false); // 수동 커밋 (처리 완료 후 커밋)
        configProps.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, consumerMaxPollIntervalMs);
        configProps.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, consumerSessionTimeoutMs);

        log.info("🔧 Consumer 설정 - MaxPollRecords: {}, MaxPollInterval: {}ms, SessionTimeout: {}ms",
                consumerMaxPollRecords, consumerMaxPollIntervalMs, consumerSessionTimeoutMs);

        return new DefaultKafkaConsumerFactory<>(configProps);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setBatchListener(true); // 배치 리스너 활성화

        // 토픽의 파티션 수를 자동 감지해서 concurrency 설정
        int partitionCount = getTopicPartitionCount(TOPIC_NAME);
        factory.setConcurrency(partitionCount); // 파티션 수만큼 Consumer 스레드 생성

        factory.getContainerProperties().setAckMode(
                org.springframework.kafka.listener.ContainerProperties.AckMode.MANUAL); // 수동 커밋
        return factory;
    }

    /**
     * Kafka 토픽의 파티션 수를 자동 감지 (재시도 포함)
     * Docker 명령어로 파티션 수를 변경하면 자동으로 감지됨:
     * docker exec kafka kafka-topics --bootstrap-server kafka:29092 --alter --topic campaign-participation-topic --partitions 3
     */
    private int getTopicPartitionCount(String topicName) {
        int maxRetries = 5;
        int retryDelayMs = 2000;

        for (int retry = 0; retry < maxRetries; retry++) {
            try {
                Map<String, Object> configs = new HashMap<>();
                configs.put("bootstrap.servers", bootstrapServers);

                try (AdminClient adminClient = AdminClient.create(configs)) {
                    DescribeTopicsResult result = adminClient.describeTopics(Collections.singletonList(topicName));
                    Map<String, TopicDescription> descriptions = result.allTopicNames().get(5, java.util.concurrent.TimeUnit.SECONDS);
                    TopicDescription description = descriptions.get(topicName);

                    if (description == null) {
                        log.warn("⚠️ 토픽 '{}' 을 찾을 수 없습니다. 재시도 {}/{}", topicName, retry + 1, maxRetries);
                        Thread.sleep(retryDelayMs);
                        continue;
                    }

                    int partitionCount = description.partitions().size();

                    log.info("🔧 Kafka 토픽 '{}' 파티션 수 자동 감지: {} → Consumer concurrency: {}",
                            topicName, partitionCount, partitionCount);

                    return partitionCount;
                }
            } catch (org.apache.kafka.common.errors.UnknownTopicOrPartitionException e) {
                log.warn("⚠️ 토픽 '{}' 이 아직 생성되지 않았습니다. 재시도 {}/{}", topicName, retry + 1, maxRetries);
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (Exception e) {
                log.warn("⚠️ 토픽 파티션 수 조회 중 오류 발생. 재시도 {}/{}: {}", retry + 1, maxRetries, e.getMessage());
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log.warn("⚠️ 최대 재시도 횟수 초과. 기본값 1 사용");
        return 1; // 기본값
    }
}
