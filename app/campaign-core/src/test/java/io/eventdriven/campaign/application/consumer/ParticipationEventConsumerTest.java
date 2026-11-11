package io.eventdriven.campaign.application.consumer;

import io.eventdriven.campaign.application.event.DlqEventPayload;
import io.eventdriven.campaign.application.event.ParticipationEvent;
import io.eventdriven.campaign.application.service.DlqMessageService;
import io.eventdriven.campaign.application.service.SlackNotificationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ParticipationEventConsumerTest {

    @Mock private JsonMapper jsonMapper;
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
                jdbcTemplate,
                slackNotificationService,
                dlqMessageService,
                meterRegistry
        );
    }

    @Test
    @DisplayName("배치 저장이 DB 장애로 실패하면 건별 처리와 DLQ 없이 배치 전체를 nack한다")
    void dbFailureOnBatch_nacksWholeBatchWithoutDlq() throws Exception {
        List<ConsumerRecord<String, String>> records = givenValidRecords(3);
        when(jdbcTemplate.batchUpdate(anyString(), anyList()))
                .thenThrow(new CannotGetJdbcConnectionException("db unavailable"));

        consumer.consumeParticipationEvent(records, acknowledgment);

        verify(acknowledgment).nack(eq(0), any(Duration.class));
        verify(acknowledgment, never()).acknowledge();
        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any());
        verify(dlqMessageService, never()).publishAndStore(any());
        assertThat(meterRegistry.get("consumer.batch.retried").tag("reason", "db_failure").counter().count())
                .isEqualTo(1D);
        assertThat(meterRegistry.get("consumer.db.transient.failures").counter().count()).isEqualTo(3D);
        assertThat(meterRegistry.find("consumer.db.committed").counter()).isNull();
        assertThat(meterRegistry.get("consumer.pending_to_success.latency").timer().count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("건별 처리 중 DB 장애가 나면 남은 행을 처리하지 않고 즉시 nack한다")
    void dbFailureDuringRowFallback_stopsAndNacks() throws Exception {
        List<ConsumerRecord<String, String>> records = givenValidRecords(3);
        when(jdbcTemplate.batchUpdate(anyString(), anyList()))
                .thenThrow(new DataIntegrityViolationException("fk violation"));
        // 다른 행의 update 호출이 strict stubbing 예외로 바뀌지 않게 lenient로 둔다.
        lenient().doThrow(new CannotGetJdbcConnectionException("db unavailable"))
                .when(jdbcTemplate).update(anyString(), eq(61L), eq(2L), eq(2L));

        consumer.consumeParticipationEvent(records, acknowledgment);

        verify(jdbcTemplate).update(anyString(), eq(61L), eq(1L), eq(1L));
        verify(jdbcTemplate, never()).update(anyString(), eq(61L), eq(3L), eq(3L));
        verify(acknowledgment).nack(eq(0), any(Duration.class));
        verify(acknowledgment, never()).acknowledge();
        verify(dlqMessageService, never()).publishAndStore(any());
    }

    @Test
    @DisplayName("정상·불량 메시지가 섞이면 정상은 DB에 저장하고 불량만 DLQ에 보관한 뒤 ACK한다")
    void mixedBatch_storesValidAndIsolatesInvalid() throws Exception {
        List<ConsumerRecord<String, String>> records = List.of(
                record("1", "valid"),
                record("2", "missing-campaign"),
                record("3", "null-user"),
                record("4", "not-json")
        );
        when(jsonMapper.readValue("valid", ParticipationEvent.class)).thenReturn(new ParticipationEvent(61L, 1L, 1L));
        when(jsonMapper.readValue("missing-campaign", ParticipationEvent.class))
                .thenReturn(new ParticipationEvent(999L, 2L, 2L));
        when(jsonMapper.readValue("null-user", ParticipationEvent.class))
                .thenReturn(new ParticipationEvent(61L, null, 3L));
        when(jsonMapper.readValue("not-json", ParticipationEvent.class)).thenThrow(new RuntimeException("bad json"));
        when(jdbcTemplate.batchUpdate(anyString(), anyList()))
                .thenThrow(new DataIntegrityViolationException("fk violation"));
        lenient().doThrow(new DataIntegrityViolationException("fk violation"))
                .when(jdbcTemplate).update(anyString(), eq(999L), eq(2L), eq(2L));
        givenDlqPayloads();

        consumer.consumeParticipationEvent(records, acknowledgment);

        verify(jdbcTemplate).update(anyString(), eq(61L), eq(1L), eq(1L));
        verify(dlqMessageService).createConsumerPayload(eq("3"), eq("null-user"), eq("INVALID_PAYLOAD"), isNull());
        verify(dlqMessageService).createConsumerPayload(eq("4"), eq("not-json"), eq("JSON_PARSE_ERROR"), eq("bad json"));
        verify(dlqMessageService).createConsumerPayload(eq("2"), anyString(), eq("INSERT_FAILED"), eq("fk violation"));
        ArgumentCaptor<DlqEventPayload> stored = ArgumentCaptor.forClass(DlqEventPayload.class);
        verify(dlqMessageService, times(3)).publishAndStore(stored.capture());
        assertThat(stored.getAllValues()).extracting(DlqEventPayload::getErrorReason)
                .containsExactlyInAnyOrder("INVALID_PAYLOAD", "JSON_PARSE_ERROR", "INSERT_FAILED");
        verify(acknowledgment).acknowledge();
        verify(acknowledgment, never()).nack(anyInt(), any(Duration.class));
        assertThat(meterRegistry.get("consumer.db.committed").counter().count()).isEqualTo(1D);
    }

    @Test
    @DisplayName("DLQ DB 저장이 실패하면 원본을 ACK하지 않고 배치 전체를 nack한다")
    void dlqStoreFailure_nacksWholeBatch() throws Exception {
        List<ConsumerRecord<String, String>> records = List.of(record("1", "not-json"));
        when(jsonMapper.readValue("not-json", ParticipationEvent.class)).thenThrow(new RuntimeException("bad json"));
        givenDlqPayloads();
        doThrow(new CannotGetJdbcConnectionException("db unavailable"))
                .when(dlqMessageService).publishAndStore(any());

        consumer.consumeParticipationEvent(records, acknowledgment);

        verify(acknowledgment).nack(eq(0), any(Duration.class));
        verify(acknowledgment, never()).acknowledge();
        verify(slackNotificationService, never()).sendDlqAlert(anyString(), anyString());
        assertThat(meterRegistry.get("consumer.batch.retried").tag("reason", "dlq_store_failure").counter().count())
                .isEqualTo(1D);
    }

    @Test
    @DisplayName("실제 null value는 NULL_VALUE로 보관하고 뒤따르는 정상 메시지와 함께 ACK한다")
    void nullRecordValue_isIsolatedAndBatchAcked() throws Exception {
        when(jsonMapper.readValue("valid", ParticipationEvent.class)).thenReturn(new ParticipationEvent(61L, 1L, 1L));
        List<ConsumerRecord<String, String>> records = List.of(record("1", null), record("2", "valid"));
        givenDlqPayloads();

        consumer.consumeParticipationEvent(records, acknowledgment);

        verify(dlqMessageService).createConsumerPayload(eq("1"), isNull(), eq("NULL_VALUE"), isNull());
        verify(jsonMapper, never()).readValue((String) isNull(), eq(ParticipationEvent.class));
        verify(jdbcTemplate).batchUpdate(anyString(), anyList());
        verify(dlqMessageService).publishAndStore(any());
        verify(acknowledgment).acknowledge();
        verify(acknowledgment, never()).nack(anyInt(), any(Duration.class));
    }

    @Test
    @DisplayName("필수 값이 없거나 0 이하인 메시지는 DB 저장을 시도하지 않고 INVALID_PAYLOAD로 보관한다")
    void invalidPayloads_skipDbAndGoToDlq() throws Exception {
        List<ConsumerRecord<String, String>> records = List.of(
                record("1", "null"),
                record("2", "no-campaign"),
                record("3", "zero-user"),
                record("4", "no-sequence")
        );
        when(jsonMapper.readValue("null", ParticipationEvent.class)).thenReturn(null);
        when(jsonMapper.readValue("no-campaign", ParticipationEvent.class))
                .thenReturn(new ParticipationEvent(null, 1L, 1L));
        when(jsonMapper.readValue("zero-user", ParticipationEvent.class))
                .thenReturn(new ParticipationEvent(61L, 0L, 1L));
        when(jsonMapper.readValue("no-sequence", ParticipationEvent.class))
                .thenReturn(new ParticipationEvent(61L, 1L, null));
        givenDlqPayloads();

        consumer.consumeParticipationEvent(records, acknowledgment);

        verify(jdbcTemplate, never()).batchUpdate(anyString(), anyList());
        verify(dlqMessageService, times(4))
                .createConsumerPayload(anyString(), anyString(), eq("INVALID_PAYLOAD"), isNull());
        verify(dlqMessageService, times(4)).publishAndStore(any());
        verify(acknowledgment).acknowledge();
        assertThat(meterRegistry.find("consumer.db.committed").counter()).isNull();
        assertThat(meterRegistry.find("consumer.pending_to_success.latency").timer()).isNull();
    }

    @Test
    @DisplayName("저장 SQL은 중복만 무시하고 필수 날짜 컬럼을 모두 채운다")
    void insertSql_ignoresOnlyDuplicatesAndFillsTimestamps() throws Exception {
        List<ConsumerRecord<String, String>> records = givenValidRecords(1);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);

        consumer.consumeParticipationEvent(records, acknowledgment);

        verify(jdbcTemplate).batchUpdate(sql.capture(), anyList());
        assertThat(sql.getValue())
                .doesNotContain("IGNORE")
                .contains("updated_at")
                .contains("ON DUPLICATE KEY UPDATE id = id");
        verify(acknowledgment).acknowledge();
        assertThat(meterRegistry.get("consumer.db.committed").counter().count()).isEqualTo(1D);
    }

    @Test
    @DisplayName("같은 캠페인·사용자가 한 배치에 두 번 오면 첫 건만 INSERT하고 배치를 ACK한다(MySQL 1869 무한 재시도 방지)")
    void inBatchDuplicate_insertsFirstOnlyAndAcks() throws Exception {
        // 11-11 실측: Bridge 재발행 사본이 원본과 같은 100건 묶음에 들어 rewrite된 한 INSERT가 1869로 거부되던 형태
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        when(jsonMapper.readValue("orig", ParticipationEvent.class)).thenReturn(new ParticipationEvent(497L, 352691L, 249046L));
        when(jsonMapper.readValue("other", ParticipationEvent.class)).thenReturn(new ParticipationEvent(497L, 352654L, 249026L));
        when(jsonMapper.readValue("copy", ParticipationEvent.class)).thenReturn(new ParticipationEvent(497L, 352691L, 249046L));
        records.add(record("352691", "orig"));
        records.add(record("352654", "other"));
        records.add(record("352691", "copy"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object[]>> args = ArgumentCaptor.forClass(List.class);

        consumer.consumeParticipationEvent(records, acknowledgment);

        verify(jdbcTemplate).batchUpdate(anyString(), args.capture());
        assertThat(args.getValue()).hasSize(2);
        assertThat(args.getValue().get(0)).containsExactly(497L, 352691L, 249046L);
        assertThat(args.getValue().get(1)).containsExactly(497L, 352654L, 249026L);
        verify(acknowledgment).acknowledge();
        verify(acknowledgment, never()).nack(anyInt(), any(Duration.class));
        verify(dlqMessageService, never()).publishAndStore(any());
        assertThat(meterRegistry.get("consumer.batch.in_batch_duplicates").counter().count()).isEqualTo(1D);
        assertThat(meterRegistry.get("consumer.db.committed").counter().count()).isEqualTo(3D);
    }

    private List<ConsumerRecord<String, String>> givenValidRecords(int count) throws Exception {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        for (long i = 1; i <= count; i++) {
            String value = "event-" + i;
            when(jsonMapper.readValue(value, ParticipationEvent.class)).thenReturn(new ParticipationEvent(61L, i, i));
            records.add(record(String.valueOf(i), value));
        }
        return records;
    }

    private void givenDlqPayloads() {
        when(dlqMessageService.createConsumerPayload(any(), any(), anyString(), any()))
                .thenAnswer(invocation -> {
                    DlqEventPayload payload = new DlqEventPayload();
                    payload.setOriginalKey(invocation.getArgument(0));
                    payload.setOriginalMessage(invocation.getArgument(1));
                    payload.setErrorReason(invocation.getArgument(2));
                    return payload;
                });
    }

    private ConsumerRecord<String, String> record(String key, String value) {
        return new ConsumerRecord<>("campaign-participation-topic", 0, 0L, key, value);
    }
}
