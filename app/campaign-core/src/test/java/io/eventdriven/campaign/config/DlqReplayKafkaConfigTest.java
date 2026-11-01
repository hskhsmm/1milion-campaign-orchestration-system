package io.eventdriven.campaign.config;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.apache.kafka.clients.producer.ProducerConfig;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DlqReplayKafkaConfigTest {
    @Test void replayLimitsDoNotChangeBridgeProducer() {
        var props=new HashMap<String,Object>();
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG,60000);
        props.put(ProducerConfig.ACKS_CONFIG,"all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,true);
        var original=new DefaultKafkaProducerFactory<String,String>(props);
        var replay=new DlqReplayKafkaConfig().dlqReplayProducerFactory(original);
        assertThat(original.getConfigurationProperties().get(ProducerConfig.MAX_BLOCK_MS_CONFIG)).isEqualTo(60000);
        assertThat(replay.getConfigurationProperties()).containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG,1000)
            .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,5000)
            .containsEntry(ProducerConfig.ACKS_CONFIG,"all")
            .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,true);
    }
}
