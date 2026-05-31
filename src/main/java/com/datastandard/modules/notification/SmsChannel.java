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
public class SmsChannel implements NotificationChannel {

    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    private final ObjectMapper objectMapper;

    @Value("${notification.sms.api-url:}")
    private String apiUrl;

    @Value("${notification.sms.api-key:}")
    private String apiKey;

    @Value("${notification.sms.sign-name:}")
    private String signName;

    @Value("${notification.sms.enabled:true}")
    private boolean enabled;

    @Override
    public String getChannelName() {
        return "SMS";
    }

    @Override
    public int getPriority() {
        return 5;
    }

    @Override
    public boolean isAvailable() {
        return enabled && StrUtil.isNotBlank(apiUrl) && StrUtil.isNotBlank(apiKey);
    }

    @Override
    public boolean supports(NotificationRequest request) {
        if (!isAvailable()) {
            return false;
        }
        return request.getRecipients().stream()
                .allMatch(this::isValidPhone);
    }

    @Override
    public Mono<NotificationResult> send(NotificationRequest request, String recipient) {
        return Mono.fromCallable(() -> {
            long startTime = System.nanoTime();
            try {
                Map<String, Object> params = new HashMap<>();
                params.put("mobile", recipient);
                params.put("content", request.getContent());
                params.put("signName", signName);
                params.put("templateCode", request.getTemplateCode());
                params.put("traceId", request.getTraceId());

                HttpResponse response = HttpRequest.post(apiUrl)
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .body(objectMapper.writeValueAsString(params))
                        .timeout(5000)
                        .execute();

                if (response.isOk()) {
                    long duration = (System.nanoTime() - startTime) / 1_000_000;
                    log.debug("SMS sent successfully to {}", recipient);
                    return NotificationResult.success(getChannelName(), recipient, duration);
                } else {
                    return NotificationResult.failure(getChannelName(), recipient,
                            "SMS API returned status: " + response.getStatus(), request.getRetryCount());
                }
            } catch (Exception e) {
                long duration = (System.nanoTime() - startTime) / 1_000_000;
                log.error("Failed to send SMS to {}: {}", recipient, e.getMessage(), e);
                return NotificationResult.failure(getChannelName(), recipient,
                        e.getMessage(), request.getRetryCount());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private boolean isValidPhone(String phone) {
        return StrUtil.isNotBlank(phone) && PHONE_PATTERN.matcher(phone).matches();
    }
}
