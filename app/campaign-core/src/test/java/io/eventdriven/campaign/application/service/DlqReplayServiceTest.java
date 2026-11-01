package io.eventdriven.campaign.application.service;
import io.eventdriven.campaign.config.BatchProperties;
import io.eventdriven.campaign.domain.entity.*;
import io.eventdriven.campaign.domain.repository.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class DlqReplayServiceTest {
    DlqMessageRepository messages=mock(DlqMessageRepository.class);
    DlqReplayExecutionRepository executions=mock(DlqReplayExecutionRepository.class);
    DlqReplayExecutionItemRepository items=mock(DlqReplayExecutionItemRepository.class);
    DlqReplayPolicyService policy=mock(DlqReplayPolicyService.class);
    @SuppressWarnings("unchecked") KafkaTemplate<String,String> kafka=mock(KafkaTemplate.class);
    DlqReplayService service=new DlqReplayService(messages,executions,items,policy,kafka,new BatchProperties(),new SimpleMeterRegistry());
    DlqReplayExecution execution;
    DlqMessageRecord message;
    @BeforeEach void setup(){
        execution=new DlqReplayExecution("test",false,1L,null,null,null,10,1);
        message=new DlqMessageRecord(DlqSourceType.CONSUMER,"topic","42","{}","INSERT_FAILED",null,1L,42L,100L,DlqReplayClassification.CONDITIONALLY_REPLAYABLE);
        ReflectionTestUtils.setField(message,"id",7L);
        when(executions.findLockedById(1L)).thenReturn(Optional.of(execution));
        when(messages.findReplayCandidateIds(any(),anyLong(),any(),any(),any(),any(),any())).thenReturn(List.of(7L));
        when(messages.findLockedById(7L)).thenReturn(Optional.of(message));
        when(policy.decide(message)).thenReturn(DlqReplayPolicyService.DlqReplayDecision.replay("42","sequence100","REPLAYABLE","test"));
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.completedFuture(null));
    }
    @Test void publishAndCheckpoint(){
        assertThat(service.processNext(1L)).isTrue();
        verify(kafka).send(anyString(),eq("42"),eq("sequence100"));
        assertThat(execution.getLastMessageId()).isEqualTo(7);
        assertThat(execution.getProcessedCount()).isEqualTo(1);
        assertThat(message.getProcessingStatus()).isEqualTo(DlqMessageProcessingStatus.REPLAYED);
    }
    @Test void dryRunDoesNotPublish(){
        ReflectionTestUtils.setField(execution,"dryRun",true);
        service.processNext(1L);
        verifyNoInteractions(kafka);
        assertThat(message.getProcessingStatus()).isEqualTo(DlqMessageProcessingStatus.PENDING);
    }
    @Test void competingExecutionAlreadyProcessed(){
        message.markReplayed(java.time.LocalDateTime.now());service.processNext(1L);
        verifyNoInteractions(kafka,policy);
    }
    @Test void repeatedBrokerOutagesNeverInvalidateNormalMessage(){
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        service.processNext(1L);
        assertThat(message.getProcessingStatus()).isEqualTo(DlqMessageProcessingStatus.PENDING);
        service.processNext(1L);service.processNext(1L);
        assertThat(message.getProcessingStatus()).isEqualTo(DlqMessageProcessingStatus.PENDING);
        assertThat(execution.getFinalFailedCount()).isZero();
        assertThat(execution.getStatus()).isEqualTo(DlqReplayExecutionStatus.FAILED);
        assertThat(execution.getLastMessageId()).isZero();
        assertThat(execution.getProcessedCount()).isZero();
    }
    @Test void resultStorageFailureMustRollBack(){
        when(items.save(any())).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(()->service.processNext(1L)).isInstanceOf(IllegalStateException.class);
        assertThat(execution.getLastMessageId()).isZero();
        assertThat(execution.getPublishFailedCount()).isZero();
    }
    @Test void stopsAtLimit(){
        ReflectionTestUtils.setField(execution,"processedCount",10);
        assertThat(service.processNext(1L)).isFalse();verifyNoInteractions(kafka,policy);
    }
}
