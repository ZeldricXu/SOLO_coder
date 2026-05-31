package com.scheduler.notification.channel.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.notification.channel.NotificationChannel;
import com.scheduler.persistence.entity.Notification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookChannel implements NotificationChannel {

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    @Override
    public String getChannelName() {
        return "WEBHOOK";
    }

    @Override
    public Mono<Boolean> send(Notification notification) {
        String webhookUrl = notification.getRecipient();
        Map<String, Object> payload = Map.of(
                "notificationId", notification.getNotificationId(),
                "type", notification.getType(),
                "subject", notification.getSubject(),
                "content", notification.getContent(),
                "templateParams", notification.getTemplateParams()
        );

        return webClientBuilder.build()
                .post()
                .uri(webhookUrl)
                .bodyValue(payload)
                .retrieve()
                .toBodilessEntity()
                .map(response -> response.getStatusCode().is2xxSuccessful())
                .doOnError(e -> log.error("Failed to send webhook to: {}", webhookUrl, e))
                .onErrorReturn(false);
    }

    @Override
    public boolean supports(String channel) {
        return "WEBHOOK".equalsIgnoreCase(channel);
    }
}
