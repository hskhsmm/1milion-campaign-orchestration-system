package io.eventdriven.campaign.application.event;

public class ParticipationEvent {
    private Long campaignId;
    private Long userId;
    private Long sequence; // v3 — Redis DECR 시점 확정된 선착순 번호 (historyId 대체)

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

    public ParticipationEvent(Long campaignId, Long userId, Long sequence) {
        this.campaignId = campaignId;
        this.userId = userId;
        this.sequence = sequence;
    }

    public Long getSequence() {
        return sequence;
    }

    public void setSequence(Long sequence) {
        this.sequence = sequence;
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
