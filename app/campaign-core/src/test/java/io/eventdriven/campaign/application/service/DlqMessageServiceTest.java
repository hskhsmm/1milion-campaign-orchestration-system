package io.eventdriven.campaign.application.service;

import io.eventdriven.campaign.application.event.DlqEventPayload;
import io.eventdriven.campaign.application.event.ParticipationEvent;
import io.eventdriven.campaign.domain.entity.DlqReplayClassification;
import io.eventdriven.campaign.domain.entity.DlqSourceType;
import io.eventdriven.campaign.domain.repository.DlqMessageRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DlqMessageServiceTest {

    @Mock private DlqMessageRepository dlqMessageRepository;
    @Mock private KafkaTemplate<String, String> kafkaTemplate;
    @Mock private JsonMapper jsonMapper;

    private SimpleMeterRegistry meterRegistry;
    private DlqMessageService dlqMessageService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        dlqMessageService = new DlqMessageService(dlqMessageRepository, kafkaTemplate, jsonMapper, meterRegistry);
    }

    @Test
    @DisplayName("dlq_message 저장이 실패하면 예외를 호출자에게 전달하고 토픽에 발행하지 않는다")
    void storeFailure_propagatesAndSkipsTopic() {
        when(dlqMessageRepository.save(any())).thenThrow(new CannotGetJdbcConnectionException("db unavailable"));

        assertThatThrownBy(() -> dlqMessageService.publishAndStore(consumerPayload()))
                .isInstanceOf(CannotGetJdbcConnectionException.class);

        verify(kafkaTemplate, never()).send(anyString(), any(), anyString());
        assertThat(meterRegistry.get("dlq.db.store.failed").tag("sourceType", "CONSUMER").counter().count())
                .isEqualTo(1D);
        assertThat(meterRegistry.find("dlq.db.stored").counter()).isNull();
    }

    @Test
    @DisplayName("토픽 발행이 비동기로 실패해도 저장 성공으로 반환하고 발행 실패 지표를 남긴다")
    void asyncTopicFailure_isRecordedWithoutFailingStore() throws Exception {
        when(jsonMapper.writeValueAsString(any())).thenReturn("{}");
        when(kafkaTemplate.send(eq(DlqMessageService.DLQ_TOPIC), any(), eq("{}")))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        dlqMessageService.publishAndStore(consumerPayload());

        verify(dlqMessageRepository).save(any());
        assertThat(meterRegistry.get("dlq.db.stored").tag("sourceType", "CONSUMER").counter().count())
                .isEqualTo(1D);
        assertThat(meterRegistry.get("dlq.topic.publish.failed").tag("sourceType", "CONSUMER").counter().count())
                .isEqualTo(1D);
    }

    @Test
    @DisplayName("INVALID_PAYLOAD와 JSON_PARSE_ERROR는 재처리 불가로 분류한다")
    void invalidReasons_areNonReplayable() throws Exception {
        when(jsonMapper.readValue(anyString(), eq(ParticipationEvent.class)))
                .thenThrow(new RuntimeException("bad json"));

        assertThat(dlqMessageService.createConsumerPayload("1", "x", "INVALID_PAYLOAD", null)
                .getReplayClassification()).isEqualTo(DlqReplayClassification.NON_REPLAYABLE);
        assertThat(dlqMessageService.createConsumerPayload("1", "x", "JSON_PARSE_ERROR", null)
                .getReplayClassification()).isEqualTo(DlqReplayClassification.NON_REPLAYABLE);
    }

    private DlqEventPayload consumerPayload() {
        DlqEventPayload payload = new DlqEventPayload();
        payload.setSourceType(DlqSourceType.CONSUMER);
        payload.setOriginalKey("1");
        payload.setOriginalMessage("{}");
        payload.setErrorReason("INSERT_FAILED");
        return payload;
    }
}
