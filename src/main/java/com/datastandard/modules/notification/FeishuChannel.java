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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class FeishuChannel implements NotificationChannel {

    private static final Pattern FEISHU_WEBHOOK_PATTERN = Pattern.compile(
            "^https://open\\.feishu\\.cn/open-apis/bot/v2/hook/.+$");

    private final ObjectMapper objectMapper;

    @Value("${notification.feishu.enabled:true}")
    private boolean enabled;

    @Value("${notification.feishu.secret:}")
    private String secret;

    @Override
    public String getChannelName() {
        return "FEISHU";
    }

    @Override
    public int getPriority() {
        return 16;
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
                .allMatch(this::isValidFeishuWebhook);
    }

    @Override
    public Mono<NotificationResult> send(NotificationRequest request, String recipient) {
        return Mono.fromCallable(() -> {
            long startTime = System.nanoTime();
            try {
                Map<String, Object> payload = buildInteractiveCardPayload(request);

                if (StrUtil.isNotBlank(secret)) {
                    long timestamp = System.currentTimeMillis() / 1000;
                    payload.put("timestamp", timestamp);
                    payload.put("sign", generateSign(timestamp, secret));
                }

                HttpResponse response = HttpRequest.post(recipient)
                        .header("Content-Type", "application/json")
                        .body(objectMapper.writeValueAsString(payload))
                        .timeout(5000)
                        .execute();

                if (response.isOk()) {
                    Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
                    Object statusCode = result.get("StatusCode");
                    Object code = result.get("code");
                    boolean success = (statusCode == null || ((Number) statusCode).intValue() == 0)
                            && (code == null || ((Number) code).intValue() == 0);

                    if (success) {
                        long duration = (System.nanoTime() - startTime) / 1_000_000;
                        log.debug("Feishu notification sent successfully");
                        return NotificationResult.success(getChannelName(), recipient, duration);
                    } else {
                        String msg = (String) result.getOrDefault("msg", "Unknown error");
                        return NotificationResult.failure(getChannelName(), recipient,
                                msg, request.getRetryCount());
                    }
                } else {
                    return NotificationResult.failure(getChannelName(), recipient,
                            "Feishu API returned status: " + response.getStatus(), request.getRetryCount());
                }
            } catch (Exception e) {
                long duration = (System.nanoTime() - startTime) / 1_000_000;
                log.error("Failed to send Feishu notification: {}", e.getMessage(), e);
                return NotificationResult.failure(getChannelName(), recipient,
                        e.getMessage(), request.getRetryCount());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> buildInteractiveCardPayload(NotificationRequest request) {
        Map<String, Object> header = new HashMap<>();
        Map<String, String> title = new HashMap<>();
        title.put("tag", "plain_text");
        title.put("content", request.getSubject());
        header.put("title", title);
        header.put("template", getTemplateColor(request));

        Map<String, Object> textElement = new HashMap<>();
        textElement.put("tag", "markdown");
        textElement.put("content", request.getContent());

        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(textElement);

        Map<String, Object> card = new HashMap<>();
        card.put("config", Map.of("wide_screen_mode", true));
        card.put("header", header);
        card.put("elements", elements);

        Map<String, Object> payload = new HashMap<>();
        payload.put("msg_type", "interactive");
        payload.put("card", card);

        return payload;
    }

    private String getTemplateColor(NotificationRequest request) {
        int priority = request.getPriority();
        if (priority >= 9) return "red";
        if (priority >= 7) return "orange";
        if (priority >= 5) return "blue";
        return "green";
    }

    private String generateSign(long timestamp, String secret) {
        String stringToSign = timestamp + "\n" + secret;
        byte[] hmacResult = cn.hutool.crypto.SecureUtil.hmacSha256(secret.getBytes()).digest(stringToSign.getBytes());
        return java.util.Base64.getEncoder().encodeToString(hmacResult);
    }

    private boolean isValidFeishuWebhook(String url) {
        return StrUtil.isNotBlank(url) && FEISHU_WEBHOOK_PATTERN.matcher(url).matches();
    }
}
