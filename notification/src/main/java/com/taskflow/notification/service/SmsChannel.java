package com.taskflow.notification.service;

import com.taskflow.notification.model.NotificationRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Slf4j
@Component
public class SmsChannel implements NotificationChannel {

    private final WebClient webClient = WebClient.builder().build();

    @Override
    public String getChannelName() {
        return "sms";
    }

    @Override
    public boolean send(NotificationRequest request, String content, String subject) throws Exception {
        log.info("Sending SMS to: {}, content: {}", request.getReceivers(), content);

        if (request.getConfig() != null && request.getConfig().containsKey("smsProviderUrl")) {
            String providerUrl = (String) request.getConfig().get("smsProviderUrl");
            try {
                webClient.post()
                        .uri(providerUrl)
                        .bodyValue(Map.of(
                                "phones", request.getReceivers(),
                                "content", content
                        ))
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();
            } catch (Exception e) {
                log.error("SMS sending failed", e);
                return false;
            }
        }

        return true;
    }
}
