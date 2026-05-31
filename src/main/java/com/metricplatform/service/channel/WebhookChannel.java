package com.metricplatform.service.channel;

import com.metricplatform.entity.SysNotificationRecord;
import com.metricplatform.service.NotificationChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookChannel implements NotificationChannel {

    private final WebClient.Builder webClientBuilder;

    @Override
    public String getChannelName() {
        return "webhook";
    }

    @Override
    public boolean send(SysNotificationRecord record) {
        try {
            String webhookUrl = record.getReceiver();
            log.info("[Webhook] 发送Webhook到: {}", webhookUrl);
            log.debug("[Webhook] Webhook内容: {}", record.getContent());

            webClientBuilder.build()
                    .post()
                    .uri(webhookUrl)
                    .bodyValue(record.getContent())
                    .retrieve()
                    .toBodilessEntity()
                    .block();

            return true;
        } catch (Exception e) {
            log.error("[Webhook] 发送Webhook失败: {}", e.getMessage(), e);
            return false;
        }
    }
}
