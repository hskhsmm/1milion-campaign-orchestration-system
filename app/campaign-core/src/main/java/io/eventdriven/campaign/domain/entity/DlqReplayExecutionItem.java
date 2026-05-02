package io.eventdriven.campaign.domain.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "dlq_replay_execution_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DlqReplayExecutionItem extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "execution_id", nullable = false)
    private DlqReplayExecution execution;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dlq_message_id", nullable = false)
    private DlqMessageRecord dlqMessage;

    @Column(name = "original_topic", nullable = false)
    private String originalTopic;

    @Column(name = "original_key")
    private String originalKey;

    @Column(name = "reason", nullable = false, length = 100)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 32)
    private DlqReplayAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 32)
    private DlqReplayItemResult result;

    @Column(name = "detail_message", length = 1000)
    private String detailMessage;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    public DlqReplayExecutionItem(
            DlqReplayExecution execution,
            DlqMessageRecord dlqMessage,
            String reason,
            DlqReplayAction action,
            DlqReplayItemResult result,
            String detailMessage,
            LocalDateTime processedAt
    ) {
        this.execution = execution;
        this.dlqMessage = dlqMessage;
        this.originalTopic = dlqMessage.getOriginalTopic();
        this.originalKey = dlqMessage.getOriginalKey();
        this.reason = reason;
        this.action = action;
        this.result = result;
        this.detailMessage = detailMessage;
        this.processedAt = processedAt;
    }
}
