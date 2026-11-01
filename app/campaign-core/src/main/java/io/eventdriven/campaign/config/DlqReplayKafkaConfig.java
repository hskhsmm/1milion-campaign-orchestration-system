package io.eventdriven.campaign.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;

/** Replay stops quickly on broker outage; Bridge/DLQ producers retain their existing settings. */
@Configuration
public class DlqReplayKafkaConfig {

    @Bean
    public ProducerFactory<String, String> dlqReplayProducerFactory(
            @Qualifier("producerFactory") ProducerFactory<String, String> original) {
        var properties = new HashMap<String, Object>(original.getConfigurationProperties());
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 1000);
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1000);
        properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5000);
        properties.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        return new DefaultKafkaProducerFactory<>(properties);
    }

    @Bean
    public KafkaTemplate<String, String> dlqReplayKafkaTemplate(
            @Qualifier("dlqReplayProducerFactory") ProducerFactory<String, String> factory) {
        return new KafkaTemplate<>(factory);
    }
}
