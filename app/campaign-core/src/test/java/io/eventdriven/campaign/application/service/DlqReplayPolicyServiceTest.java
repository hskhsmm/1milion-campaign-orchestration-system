package io.eventdriven.campaign.application.service;

import io.eventdriven.campaign.application.event.ParticipationEvent;
import io.eventdriven.campaign.domain.entity.Campaign;
import io.eventdriven.campaign.domain.entity.DlqMessageRecord;
import io.eventdriven.campaign.domain.entity.DlqReplayAction;
import io.eventdriven.campaign.domain.entity.DlqReplayClassification;
import io.eventdriven.campaign.domain.entity.DlqSourceType;
import io.eventdriven.campaign.domain.entity.ParticipationStatus;
import io.eventdriven.campaign.domain.repository.CampaignRepository;
import io.eventdriven.campaign.domain.repository.ParticipationHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DlqReplayPolicyServiceTest {

    private static final Long CAMPAIGN_ID = 1L;
    private static final Long USER_ID = 42L;
    private static final Long SEQUENCE = 100L;

    @Mock private CampaignRepository campaignRepository;
    @Mock private ParticipationHistoryRepository participationHistoryRepository;
    @Mock private JsonMapper jsonMapper;

    private DlqReplayPolicyService service;

    @BeforeEach
    void setUp() {
        service = new DlqReplayPolicyService(campaignRepository, participationHistoryRepository, jsonMapper);
    }

    @Test
    @DisplayName("NON_REPLAYABLE 메시지는 재처리하지 않고 최종 실패로 분류한다")
    void decide_nonReplayable_finalFail() {
        DlqMessageRecord message = message(DlqReplayClassification.NON_REPLAYABLE, CAMPAIGN_ID, USER_ID, SEQUENCE);

        DlqReplayPolicyService.DlqReplayDecision decision = service.decide(message);

        assertThat(decision.action()).isEqualTo(DlqReplayAction.FINAL_FAIL);
        assertThat(decision.reason()).isEqualTo("NON_REPLAYABLE");
        verifyNoInteractions(campaignRepository, participationHistoryRepository, jsonMapper);
    }

    @Test
    @DisplayName("campaignId/userId/sequence가 없으면 재처리하지 않는다")
    void decide_missingRequiredFields_finalFail() {
        DlqMessageRecord message = message(DlqReplayClassification.REPLAYABLE, CAMPAIGN_ID, null, SEQUENCE);

        DlqReplayPolicyService.DlqReplayDecision decision = service.decide(message);

        assertThat(decision.action()).isEqualTo(DlqReplayAction.FINAL_FAIL);
        assertThat(decision.reason()).isEqualTo("MISSING_REQUIRED_FIELDS");
        verifyNoInteractions(campaignRepository, participationHistoryRepository, jsonMapper);
    }

    @Test
    @DisplayName("존재하지 않는 캠페인의 메시지는 최종 실패로 분류한다")
    void decide_campaignNotFound_finalFail() {
        DlqMessageRecord message = message(DlqReplayClassification.REPLAYABLE, CAMPAIGN_ID, USER_ID, SEQUENCE);
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.empty());

        DlqReplayPolicyService.DlqReplayDecision decision = service.decide(message);

        assertThat(decision.action()).isEqualTo(DlqReplayAction.FINAL_FAIL);
        assertThat(decision.reason()).isEqualTo("CAMPAIGN_NOT_FOUND");
        verifyNoInteractions(participationHistoryRepository, jsonMapper);
    }

    @Test
    @DisplayName("이미 SUCCESS 기록이 있으면 중복 방지를 위해 skip한다")
    void decide_alreadySuccess_skip() {
        DlqMessageRecord message = message(DlqReplayClassification.REPLAYABLE, CAMPAIGN_ID, USER_ID, SEQUENCE);
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(mock(Campaign.class)));
        when(participationHistoryRepository.existsSuccessHistory(CAMPAIGN_ID, USER_ID, ParticipationStatus.SUCCESS))
                .thenReturn(true);

        DlqReplayPolicyService.DlqReplayDecision decision = service.decide(message);

        assertThat(decision.action()).isEqualTo(DlqReplayAction.SKIP);
        assertThat(decision.reason()).isEqualTo("ALREADY_SUCCESS");
        verifyNoInteractions(jsonMapper);
    }

    @Test
    @DisplayName("재처리 가능한 메시지는 userId 키로 원래 토픽에 재발행하도록 결정한다")
    void decide_replayable_replay() throws Exception {
        DlqMessageRecord message = message(DlqReplayClassification.REPLAYABLE, CAMPAIGN_ID, USER_ID, SEQUENCE);
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(mock(Campaign.class)));
        when(participationHistoryRepository.existsSuccessHistory(CAMPAIGN_ID, USER_ID, ParticipationStatus.SUCCESS))
                .thenReturn(false);
        when(jsonMapper.writeValueAsString(any(ParticipationEvent.class)))
                .thenReturn("{\"campaignId\":1,\"userId\":42,\"sequence\":100}");

        DlqReplayPolicyService.DlqReplayDecision decision = service.decide(message);

        assertThat(decision.action()).isEqualTo(DlqReplayAction.REPLAY);
        assertThat(decision.reason()).isEqualTo("REPLAYABLE");
        assertThat(decision.replayKey()).isEqualTo(String.valueOf(USER_ID));
        assertThat(decision.replayPayload()).contains("\"campaignId\":1");
        verify(jsonMapper).writeValueAsString(any(ParticipationEvent.class));
    }

    private DlqMessageRecord message(
            DlqReplayClassification replayClassification,
            Long campaignId,
            Long userId,
            Long sequence
    ) {
        return new DlqMessageRecord(
                DlqSourceType.CONSUMER,
                "campaign-participation-topic",
                userId != null ? String.valueOf(userId) : null,
                "{\"campaignId\":1,\"userId\":42,\"sequence\":100}",
                "INSERT_FAILED",
                null,
                campaignId,
                userId,
                sequence,
                replayClassification
        );
    }
}
