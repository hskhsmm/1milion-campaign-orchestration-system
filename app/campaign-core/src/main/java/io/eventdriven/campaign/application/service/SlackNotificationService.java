package io.eventdriven.campaign.application.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Slack Incoming Webhook 알림 서비스
 *
 * DLQ 적재 시 Slack 채널로 알림 발송.
 * webhook-url 미설정 시 로그만 남기고 스킵 (로컬/테스트 환경 안전).
 * SSM Parameter Store → 환경변수 SLACK_WEBHOOK_URL → slack.webhook-url 순으로 주입.
 */
@Slf4j
@Service
public class SlackNotificationService {

    @Value("${slack.webhook-url:}")
    private String webhookUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * DLQ 적재 알림 발송
     *
     * @param reason  알림 사유 (예: "Bridge Produce MAX_RETRY 초과")
     * @param detail  상세 정보 (historyId 또는 campaignId 포함)
     */
    public void sendDlqAlert(String reason, String detail) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.warn("[Slack 미설정] DLQ 알림 스킵. reason={}, detail={}", reason, detail);
            return;
        }
        try {
            String text = String.format("[DLQ 알림] %s | %s", reason, detail);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.postForEntity(
                    webhookUrl,
                    new HttpEntity<>(Map.of("text", text), headers),
                    String.class
            );
            log.info("Slack 알림 전송 완료. reason={}", reason);
        } catch (Exception e) {
            log.error("Slack 알림 전송 실패. reason={}", reason, e);
        }
    }
}
