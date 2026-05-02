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
import java.time.temporal.ChronoUnit;
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
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime cutoff = now.minusMinutes(5);
            List<ParticipationHistory> pendingList =
                    participationHistoryRepository.findByStatusAndCreatedAtBefore(
                            ParticipationStatus.PENDING, cutoff);

            if (pendingList.isEmpty()) {
                log.info("Pending recovery completed. candidates=0, republished=0");
                return RepeatStatus.FINISHED;
            }

            int successCount = 0;
            for (ParticipationHistory history : pendingList) {
                String message = buildMessage(history);
                Long ageMinutes = history.getCreatedAt() == null
                        ? null
                        : Math.max(0L, ChronoUnit.MINUTES.between(history.getCreatedAt(), now));
                try {
                    kafkaTemplate.send(KafkaConfig.TOPIC_NAME, String.valueOf(history.getCampaign().getId()), message)
                            .whenComplete((result, ex) -> {
                                if (ex != null) {
                                    log.error(
                                            "Pending recovery publish result. historyId={}, campaignId={}, ageMinutes={}, republished=false",
                                            history.getId(),
                                            history.getCampaign().getId(),
                                            ageMinutes,
                                            ex
                                    );
                                } else {
                                    log.info(
                                            "Pending recovery publish result. historyId={}, campaignId={}, ageMinutes={}, republished=true",
                                            history.getId(),
                                            history.getCampaign().getId(),
                                            ageMinutes
                                    );
                                }
                            });
                    successCount++;
                } catch (Exception e) {
                    log.warn(
                            "Pending recovery publish result. historyId={}, campaignId={}, ageMinutes={}, republished=false",
                            history.getId(),
                            history.getCampaign().getId(),
                            ageMinutes,
                            e
                    );
                }
            }

            log.info("Pending recovery completed. candidates={}, republished={}", pendingList.size(), successCount);

            return RepeatStatus.FINISHED;
        };
    }

    private String buildMessage(ParticipationHistory history) {
        try {
            Map<String, Object> msg = new HashMap<>();
            msg.put("campaignId", history.getCampaign().getId());
            msg.put("userId", history.getUserId());
            msg.put("sequence", history.getSequence());
            return jsonMapper.writeValueAsString(msg);
        } catch (Exception e) {
            throw new RuntimeException("Message serialization failed. historyId=" + history.getId(), e);
        }
    }
}
