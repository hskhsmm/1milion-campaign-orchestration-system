package io.eventdriven.campaign.domain.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "participation_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ParticipationHistory extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id", nullable = false)
    private Campaign campaign;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ParticipationStatus status;

    // Kafka 메타데이터 (순서 분석용)
    @Column(name = "kafka_offset")
    private Long kafkaOffset;

    @Column(name = "kafka_partition")
    private Integer kafkaPartition;

    @Column(name = "kafka_timestamp")
    private Long kafkaTimestamp;

    // 처리 시작 시간 (나노초 - 순서 분석용)
    @Column(name = "processing_started_at_nanos")
    private Long processingStartedAtNanos;

    // 처리 순서 번호 (Consumer가 처리한 순서, 완벽한 순서 보장 증명용)
    @Column(name = "processing_sequence")
    private Long processingSequence;

    // 선착순 번호 (Redis DECR 반환값 기반, v2)
    @Column(name = "sequence")
    private Long sequence;

    // PENDING 선점용 생성자 (v2 — A파트에서 사용)
    // sequence 파라미터 추가
    public ParticipationHistory(Campaign campaign, Long userId, Long sequence) {
        this.campaign = campaign;
        this.userId = userId;
        this.sequence = sequence;
        this.status = ParticipationStatus.PENDING;
    }

    // 기존 생성자 (하위 호환성)
    public ParticipationHistory(Campaign campaign, Long userId, ParticipationStatus status) {
        this.campaign = campaign;
        this.userId = userId;
        this.status = status;
    }

    // Kafka 메타데이터 포함 생성자
    public ParticipationHistory(Campaign campaign, Long userId, ParticipationStatus status,
                                Long kafkaOffset, Integer kafkaPartition, Long kafkaTimestamp,
                                Long processingSequence) {
        this.campaign = campaign;
        this.userId = userId;
        this.status = status;
        this.kafkaOffset = kafkaOffset;
        this.kafkaPartition = kafkaPartition;
        this.kafkaTimestamp = kafkaTimestamp;
        this.processingStartedAtNanos = System.nanoTime(); // 처리 시작 시간 기록
        this.processingSequence = processingSequence; // 처리 순서 번호 (완벽한 순서 보장)
    }
}
