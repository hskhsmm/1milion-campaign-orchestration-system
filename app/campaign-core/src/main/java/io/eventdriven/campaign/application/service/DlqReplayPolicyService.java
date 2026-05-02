package io.eventdriven.campaign.application.service;

import io.eventdriven.campaign.application.event.ParticipationEvent;
import io.eventdriven.campaign.domain.entity.DlqMessageRecord;
import io.eventdriven.campaign.domain.entity.DlqReplayAction;
import io.eventdriven.campaign.domain.entity.DlqReplayClassification;
import io.eventdriven.campaign.domain.entity.ParticipationStatus;
import io.eventdriven.campaign.domain.repository.CampaignRepository;
import io.eventdriven.campaign.domain.repository.ParticipationHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DlqReplayPolicyService {

    private final CampaignRepository campaignRepository;
    private final ParticipationHistoryRepository participationHistoryRepository;
    private final tools.jackson.databind.json.JsonMapper jsonMapper;

    public DlqReplayDecision decide(DlqMessageRecord message) {
        if (message.getReplayClassification() == DlqReplayClassification.NON_REPLAYABLE) {
            return DlqReplayDecision.finalFail("NON_REPLAYABLE", "Payload is not safe to replay");
        }

        Long campaignId = message.getCampaignId();
        Long userId = message.getUserId();
        Long sequence = message.getSequence();

        if (campaignId == null || userId == null || sequence == null) {
            return DlqReplayDecision.finalFail("MISSING_REQUIRED_FIELDS", "campaignId/userId/sequence must exist");
        }

        if (campaignRepository.findById(campaignId).isEmpty()) {
            return DlqReplayDecision.finalFail("CAMPAIGN_NOT_FOUND", "Campaign does not exist");
        }

        if (participationHistoryRepository.existsSuccessHistory(
                campaignId, userId, ParticipationStatus.SUCCESS)) {
            return DlqReplayDecision.skip("ALREADY_SUCCESS", "Participation already succeeded");
        }

        try {
            String replayPayload = jsonMapper.writeValueAsString(new ParticipationEvent(campaignId, userId, sequence));
            return DlqReplayDecision.replay(String.valueOf(userId), replayPayload,
                    "REPLAYABLE", "Replay to main participation topic");
        } catch (Exception e) {
            return DlqReplayDecision.finalFail("SERIALIZATION_FAILED", e.getMessage());
        }
    }

    public record DlqReplayDecision(
            DlqReplayAction action,
            String reason,
            String detail,
            String replayKey,
            String replayPayload
    ) {
        public static DlqReplayDecision replay(String replayKey, String replayPayload, String reason, String detail) {
            return new DlqReplayDecision(DlqReplayAction.REPLAY, reason, detail, replayKey, replayPayload);
        }

        public static DlqReplayDecision skip(String reason, String detail) {
            return new DlqReplayDecision(DlqReplayAction.SKIP, reason, detail, null, null);
        }

        public static DlqReplayDecision finalFail(String reason, String detail) {
            return new DlqReplayDecision(DlqReplayAction.FINAL_FAIL, reason, detail, null, null);
        }
    }
}
