package io.eventdriven.campaign.application.event;

public class ParticipationEvent {
    private Long campaignId;
    private Long userId;
    private Long historyId; // v2 — PENDING 선점 후 획득한 PK

    // Kafka 메타데이터 (Consumer에서 설정)
    private Long kafkaOffset;
    private Integer kafkaPartition;
    private Long kafkaTimestamp;

    public ParticipationEvent() {
    }

    public ParticipationEvent(Long campaignId, Long userId) {
        this.campaignId = campaignId;
        this.userId = userId;
    }

    public ParticipationEvent(Long campaignId, Long userId, Long historyId) {
        this.campaignId = campaignId;
        this.userId = userId;
        this.historyId = historyId;
    }

    public Long getHistoryId() {
        return historyId;
    }

    public void setHistoryId(Long historyId) {
        this.historyId = historyId;
    }

    public Long getCampaignId() {
        return campaignId;
    }

    public void setCampaignId(Long campaignId) {
        this.campaignId = campaignId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getKafkaOffset() {
        return kafkaOffset;
    }

    public void setKafkaOffset(Long kafkaOffset) {
        this.kafkaOffset = kafkaOffset;
    }

    public Integer getKafkaPartition() {
        return kafkaPartition;
    }

    public void setKafkaPartition(Integer kafkaPartition) {
        this.kafkaPartition = kafkaPartition;
    }

    public Long getKafkaTimestamp() {
        return kafkaTimestamp;
    }

    public void setKafkaTimestamp(Long kafkaTimestamp) {
        this.kafkaTimestamp = kafkaTimestamp;
    }

}
