package io.eventdriven.campaign.application.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Slf4j
@Service
public class SlackNotificationService {

    @Value("${slack.webhook-url:}")
    private String webhookUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    @Async("slackTaskExecutor")
    public void sendDlqAlert(String reason, String detail) {
        sendAlert("[DLQ 알림] " + reason, detail);
    }

    @Async("slackTaskExecutor")
    public void sendBatchAlert(String title, String detail) {
        sendAlert("[Batch 알림] " + title, detail);
    }

    private void sendAlert(String title, String detail) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.warn("[Slack 미설정] 알림 스킵. title={}, detail={}", title, detail);
            return;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.postForEntity(
                    webhookUrl,
                    new HttpEntity<>(Map.of("text", title + " | " + detail), headers),
                    String.class
            );
            log.info("Slack 알림 전송 완료. title={}", title);
        } catch (Exception e) {
            log.error("Slack 알림 전송 실패. title={}", title, e);
        }
    }
}
