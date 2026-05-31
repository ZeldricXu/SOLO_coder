package com.taskflow.notification.service;

import com.taskflow.notification.model.NotificationRequest;
import com.taskflow.common.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Slf4j
@Component
public class DingTalkChannel implements NotificationChannel {

    private final WebClient webClient = WebClient.builder().build();

    @Override
    public String getChannelName() {
        return "dingtalk";
    }

    @Override
    public boolean send(NotificationRequest request, String content, String subject) throws Exception {
        log.info("Sending DingTalk to: {}, content: {}", request.getReceivers(), content);

        if (request.getConfig() != null && request.getConfig().containsKey("webhookUrl")) {
            String webhookUrl = (String) request.getConfig().get("webhookUrl");
            try {
                Map<String, Object> payload = Map.of(
                        "msgtype", "text",
                        "text", Map.of("content", content),
                        "at", Map.of("atMobiles", request.getReceivers(), "isAtAll", false)
                );

                webClient.post()
                        .uri(webhookUrl)
                        .bodyValue(JsonUtils.toJson(payload))
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();
            } catch (Exception e) {
                log.error("DingTalk sending failed", e);
                return false;
            }
        }

        return true;
    }
}
