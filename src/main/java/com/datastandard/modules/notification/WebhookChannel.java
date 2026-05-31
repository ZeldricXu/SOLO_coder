package com.datastandard.modules.notification;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.datastandard.modules.notification.dto.NotificationRequest;
import com.datastandard.modules.notification.dto.NotificationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookChannel implements NotificationChannel {

    private static final Pattern URL_PATTERN = Pattern.compile(
            "^https?://.+$");

    private final ObjectMapper objectMapper;

    @Value("${notification.webhook.enabled:true}")
    private boolean enabled;

    @Value("${notification.webhook.timeout:10000}")
    private int timeout;

    @Override
    public String getChannelName() {
        return "WEBHOOK";
    }

    @Override
    public int getPriority() {
        return 20;
    }

    @Override
    public boolean isAvailable() {
        return enabled;
    }

    @Override
    public boolean supports(NotificationRequest request) {
        if (!isAvailable()) {
            return false;
        }
        return request.getRecipients().stream()
                .allMatch(this::isValidUrl);
    }

    @Override
    public Mono<NotificationResult> send(NotificationRequest request, String recipient) {
        return Mono.fromCallable(() -> {
            long startTime = System.nanoTime();
            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("type", request.getType());
                payload.put("subject", request.getSubject());
                payload.put("content", request.getContent());
                payload.put("sender", request.getSender());
                payload.put("traceId", request.getTraceId());
                payload.put("priority", request.getPriority());
                if (request.getMetadata() != null) {
                    payload.put("metadata", request.getMetadata());
                }
                payload.put("timestamp", System.currentTimeMillis());

                HttpResponse response = HttpRequest.post(recipient)
                        .header("Content-Type", "application/json")
                        .header("X-Trace-Id", request.getTraceId())
                        .body(objectMapper.writeValueAsString(payload))
                        .timeout(timeout)
                        .execute();

                if (response.isOk()) {
                    long duration = (System.nanoTime() - startTime) / 1_000_000;
                    log.debug("Webhook sent successfully to {}", recipient);
                    return NotificationResult.success(getChannelName(), recipient, duration);
                } else {
                    return NotificationResult.failure(getChannelName(), recipient,
                            "Webhook returned status: " + response.getStatus(), request.getRetryCount());
                }
            } catch (Exception e) {
                long duration = (System.nanoTime() - startTime) / 1_000_000;
                log.error("Failed to send webhook to {}: {}", recipient, e.getMessage(), e);
                return NotificationResult.failure(getChannelName(), recipient,
                        e.getMessage(), request.getRetryCount());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private boolean isValidUrl(String url) {
        return StrUtil.isNotBlank(url) && URL_PATTERN.matcher(url).matches();
    }
}
