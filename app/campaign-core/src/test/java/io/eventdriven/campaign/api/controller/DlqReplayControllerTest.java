package io.eventdriven.campaign.api.controller;

import io.eventdriven.campaign.application.service.*;
import io.eventdriven.campaign.domain.entity.DlqReplayExecution;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.launch.JobLauncher;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DlqReplayControllerTest {
    final DlqReplayService service=mock(DlqReplayService.class);
    final JobLauncher launcher=mock(JobLauncher.class);
    final DlqReplayVerificationService verification=mock(DlqReplayVerificationService.class);
    final DlqReplayController controller=new DlqReplayController(launcher,mock(Job.class),service,verification);
    @Test void missingResumeReturns404() throws Exception {
        when(service.getExecution(999L)).thenThrow(new IllegalArgumentException("not found"));
        assertThat(controller.resume(999L,false).getStatusCode().value()).isEqualTo(404);
        verifyNoInteractions(launcher);
    }
    @Test void missingVerificationReturns404() {
        when(service.getExecution(999L)).thenThrow(new IllegalArgumentException("not found"));
        assertThat(controller.verification(999,0,100).getStatusCode().value()).isEqualTo(404);
        verifyNoInteractions(verification);
    }
    @Test void runningWorkerRequiresExplicitStoppedConfirmation() throws Exception {
        var e=new DlqReplayExecution("test",false,null,null,null,null,10,1);
        e.markRunning(java.time.LocalDateTime.now());
        when(service.getExecution(1L)).thenReturn(e);
        assertThat(controller.resume(1L,false).getStatusCode().value()).isEqualTo(409);
        verifyNoInteractions(launcher);
    }
    @Test void completedExecutionCannotResume() throws Exception {
        var e=new DlqReplayExecution("test",false,null,null,null,null,10,1);
        e.markCompleted(java.time.LocalDateTime.now());
        when(service.getExecution(1L)).thenReturn(e);
        assertThat(controller.resume(1L,true).getStatusCode().value()).isEqualTo(409);
    }
    @Test void verificationFailureIsNotMisreportedAsMissingExecution() {
        when(service.getExecution(1L)).thenReturn(new DlqReplayExecution("test",false,null,null,null,null,10,1));
        when(verification.verify(1,0,100)).thenThrow(new IllegalArgumentException("invalid verification input"));
        assertThatThrownBy(()->controller.verification(1,0,100)).isInstanceOf(IllegalArgumentException.class);
    }
}
