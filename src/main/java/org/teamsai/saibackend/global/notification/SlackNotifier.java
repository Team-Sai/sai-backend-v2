package org.teamsai.saibackend.global.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

@Slf4j
@Component
public class SlackNotifier {

    private final RestClient restClient;

    public SlackNotifier() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Value("${slack.batch-notification.webhook-url:}")
    private String webhookUrl;

    @Value("${slack.batch-notification.enabled:false}")
    private boolean enabled;

    public void send(String message) {
        trySend(message);
    }

    public boolean trySend(String message) {
        if (!enabled || webhookUrl == null || webhookUrl.isBlank()) {
            log.debug("[SlackNotifier] Slack 알림 비활성화 또는 webhook URL 없음, 전송 스킵");  // warn → debug로 복구
            return false;
        }
        try {
            restClient.post()
                    .uri(webhookUrl)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(Map.of("text", message))
                    .retrieve()
                    .toBodilessEntity();
            log.info("[SlackNotifier] Slack 알림 전송 성공");
            return true;
        } catch (Exception e) {
            log.warn("[SlackNotifier] Slack 알림 전송 실패", e);
            return false;
        }
    }
}