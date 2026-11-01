package io.eventdriven.campaign.application.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.Map;

/** Read-only evidence: republication is not the same as final participation recovery. */
@Service
@RequiredArgsConstructor
public class DlqReplayVerificationService {
    private final JdbcTemplate jdbc;
    public Map<String,Object> verify(long executionId, long afterId, int size) {
        int limit=Math.max(1,Math.min(size,100));
        String from=" FROM dlq_replay_execution_item i JOIN dlq_message d ON d.id=i.dlq_message_id "
                +"LEFT JOIN participation_history h ON h.campaign_id=d.campaign_id AND h.user_id=d.user_id "
                +"AND h.sequence=d.sequence_no AND h.status='SUCCESS' WHERE i.execution_id=? ";
        var counts=jdbc.queryForMap("SELECT COUNT(*) AS recordedItems, "
                +"COALESCE(SUM(i.action='REPLAY' AND i.result='SUCCESS'),0) AS published, "
                +"COALESCE(SUM(i.result<>'DRY_RUN' AND h.id IS NOT NULL),0) AS dbConfirmed"+from,executionId);
        var rows=jdbc.queryForList("SELECT i.id AS itemId,d.id AS dlqId,d.campaign_id AS campaignId,"
                +"d.user_id AS userId,d.sequence_no AS sequence,i.action,i.result,"
                +"(i.result<>'DRY_RUN' AND h.id IS NOT NULL) AS dbConfirmed"+from
                +"AND i.id>? ORDER BY i.id LIMIT ?",executionId,Math.max(0,afterId),limit);
        return Map.of("summary",counts,"items",rows,"pageSize",limit,
                "meaning","DB confirmation requires the original campaign, user and sequence; job completion alone is not recovery");
    }
}
