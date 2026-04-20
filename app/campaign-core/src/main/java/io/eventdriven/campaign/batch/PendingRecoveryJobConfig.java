package io.eventdriven.campaign.batch;

import io.eventdriven.campaign.config.KafkaConfig;
import io.eventdriven.campaign.domain.entity.ParticipationHistory;
import io.eventdriven.campaign.domain.entity.ParticipationStatus;
import io.eventdriven.campaign.domain.repository.ParticipationHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class PendingRecoveryJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final ParticipationHistoryRepository participationHistoryRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JsonMapper jsonMapper;

    @Bean
    public Job pendingRecoveryJob(Step pendingRecoveryStep) {
        return new JobBuilder("pendingRecoveryJob", jobRepository)
                .start(pendingRecoveryStep)
                .build();
    }

    @Bean
    public Step pendingRecoveryStep() {
        return new StepBuilder("pendingRecoveryStep", jobRepository)
                .tasklet(pendingRecoveryTasklet(), transactionManager)
                .build();
    }

    @Bean
    public Tasklet pendingRecoveryTasklet() {
        return (contribution, chunkContext) -> {
            LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);
            List<ParticipationHistory> pendingList =
                    participationHistoryRepository.findByStatusAndCreatedAtBefore(
                            ParticipationStatus.PENDING, cutoff);

            if (pendingList.isEmpty()) {
                return RepeatStatus.FINISHED;
            }

            List<Long> failIds = new ArrayList<>();
            for (ParticipationHistory history : pendingList) {
                String message = buildMessage(history);
                try {
                    // Redis Queue 대신 Kafka 직접 발행
                    // 이유: 재고 소진 시 active:campaigns에서 SREM → Bridge가 큐를 드레인하지 않음
                    //       Batch 복구 대상은 소량이므로 Bridge 우회해도 부하 없음
                    kafkaTemplate.send(KafkaConfig.TOPIC_NAME, String.valueOf(history.getCampaign().getId()), message)
                            .whenComplete((result, ex) -> {
                                if (ex != null) {
                                    log.error("PENDING 재발행 실패 → FAIL 처리 예정. historyId={}", history.getId(), ex);
                                } else {
                                    log.info("PENDING 재발행 성공. historyId={}", history.getId());
                                }
                            });
                } catch (Exception e) {
                    failIds.add(history.getId());
                    log.warn("PENDING 재발행 실패 → FAIL 처리. historyId={}, campaignId={}",
                            history.getId(), history.getCampaign().getId(), e);
                }
            }

            if (!failIds.isEmpty()) {
                participationHistoryRepository.bulkUpdateFail(failIds);
            }

            log.info("PENDING 재처리 완료. 전체={}, 재발행={}, FAIL={}",
                    pendingList.size(), pendingList.size() - failIds.size(), failIds.size());

            return RepeatStatus.FINISHED;
        };
    }

    private String buildMessage(ParticipationHistory history) {
        try {
            Map<String, Object> msg = new HashMap<>();
            msg.put("campaignId", history.getCampaign().getId());
            msg.put("userId", history.getUserId());
            msg.put("historyId", history.getId());
            return jsonMapper.writeValueAsString(msg);
        } catch (Exception e) {
            throw new RuntimeException("메시지 직렬화 실패. historyId=" + history.getId(), e);
        }
    }
}
