package io.eventdriven.campaign.application.consumer;

import io.eventdriven.campaign.application.event.ParticipationEvent;
import io.eventdriven.campaign.application.service.DlqMessageService;
import io.eventdriven.campaign.application.service.SlackNotificationService;
import io.eventdriven.campaign.domain.repository.ParticipationHistoryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ParticipationEventConsumerTest {

    @Mock private JsonMapper jsonMapper;
    @Mock private ParticipationHistoryRepository participationHistoryRepository;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private SlackNotificationService slackNotificationService;
    @Mock private DlqMessageService dlqMessageService;
    @Mock private Acknowledgment acknowledgment;

    private SimpleMeterRegistry meterRegistry;
    private ParticipationEventConsumer consumer;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        consumer = new ParticipationEventConsumer(
                jsonMapper,
                participationHistoryRepository,
                jdbcTemplate,
                slackNotificationService,
                dlqMessageService,
                meterRegistry
        );
    }

    @Test
    @DisplayName("DB 일시 실패 메트릭은 실패 batch가 아니라 실패 이벤트 수를 기록한다")
    void consumeParticipationEvent_recordsTransientFailureEventCount() throws Exception {
        ParticipationEvent first = new ParticipationEvent(61L, 1L, 1L);
        ParticipationEvent second = new ParticipationEvent(61L, 2L, 2L);
        ParticipationEvent third = new ParticipationEvent(61L, 3L, 3L);
        List<ConsumerRecord<String, String>> records = List.of(
                record("1", "first"),
                record("2", "second"),
                record("3", "third")
        );

        when(jsonMapper.readValue("first", ParticipationEvent.class)).thenReturn(first);
        when(jsonMapper.readValue("second", ParticipationEvent.class)).thenReturn(second);
        when(jsonMapper.readValue("third", ParticipationEvent.class)).thenReturn(third);
        when(jsonMapper.writeValueAsString(any())).thenReturn("{}");
        when(jdbcTemplate.batchUpdate(anyString(), anyList())).thenThrow(new RuntimeException("db unavailable"));
        doNothing()
                .doThrow(new RuntimeException("db unavailable"))
                .doThrow(new RuntimeException("db unavailable"))
                .when(participationHistoryRepository)
                .insertSuccess(anyLong(), anyLong(), anyLong());
        doAnswer(invocation -> {
            assertThat(meterRegistry.get("consumer.pending_to_success.latency").timer().count())
                    .isEqualTo(1L);
            return null;
        }).when(slackNotificationService).sendDlqAlert(anyString(), anyString());

        consumer.consumeParticipationEvent(records, acknowledgment);

        assertThat(meterRegistry.get("consumer.kafka.records.polled").counter().count()).isEqualTo(3D);
        assertThat(meterRegistry.get("consumer.events.parsed").counter().count()).isEqualTo(3D);
        assertThat(meterRegistry.get("consumer.db.committed").counter().count()).isEqualTo(1D);
        assertThat(meterRegistry.get("consumer.db.transient.failures").counter().count()).isEqualTo(2D);
        assertThat(meterRegistry.get("consumer.db.commit.batch.size").summary().totalAmount()).isEqualTo(1D);
        assertThat(meterRegistry.get("consumer.pending_to_success.latency").timer().count()).isEqualTo(1L);
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    @DisplayName("DB 처리 대상이 없는 batch는 DB 지연 시간에 포함하지 않는다")
    void consumeParticipationEvent_doesNotRecordDbLatencyWhenAllRecordsFailParsing() throws Exception {
        List<ConsumerRecord<String, String>> records = List.of(record("1", "invalid"));
        when(jsonMapper.readValue("invalid", ParticipationEvent.class))
                .thenThrow(new RuntimeException("invalid json"));

        consumer.consumeParticipationEvent(records, acknowledgment);

        assertThat(meterRegistry.find("consumer.pending_to_success.latency").timer()).isNull();
        verify(jdbcTemplate, never()).batchUpdate(anyString(), anyList());
        verify(acknowledgment).acknowledge();
    }

    private ConsumerRecord<String, String> record(String key, String value) {
        return new ConsumerRecord<>("campaign-participation-topic", 0, 0L, key, value);
    }
}
