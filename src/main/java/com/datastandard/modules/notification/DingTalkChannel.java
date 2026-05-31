package com.datastandard.modules.notification;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
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
public class DingTalkChannel implements NotificationChannel {

    private static final Pattern DINGTALK_WEBHOOK_PATTERN = Pattern.compile(
            "^https://oapi\\.dingtalk\\.com/robot/send\\?access_token=.+$");

    private final ObjectMapper objectMapper;

    @Value("${notification.dingtalk.enabled:true}")
    private boolean enabled;

    @Value("${notification.dingtalk.app-key:}")
    private String appKey;

    @Value("${notification.dingtalk.app-secret:}")
    private String appSecret;

    @Override
    public String getChannelName() {
        return "DINGTALK";
    }

    @Override
    public int getPriority() {
        return 15;
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
                .allMatch(this::isValidDingTalkWebhook);
    }

    @Override
    public Mono<NotificationResult> send(NotificationRequest request, String recipient) {
        return Mono.fromCallable(() -> {
            long startTime = System.nanoTime();
            try {
                String webhookUrl = recipient;
                if (StrUtil.isNotBlank(appSecret)) {
                    long timestamp = System.currentTimeMillis();
                    String sign = generateSign(timestamp, appSecret);
                    webhookUrl = recipient + "&timestamp=" + timestamp + "&sign=" + sign;
                }

                Map<String, Object> payload = buildMarkdownPayload(request);

                HttpResponse response = HttpRequest.post(webhookUrl)
                        .header("Content-Type", "application/json")
                        .body(objectMapper.writeValueAsString(payload))
                        .timeout(5000)
                        .execute();

                if (response.isOk()) {
                    Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
                    if (result.containsKey("errcode") && ((Number) result.get("errcode")).intValue() == 0) {
                        long duration = (System.nanoTime() - startTime) / 1_000_000;
                        log.debug("DingTalk notification sent successfully");
                        return NotificationResult.success(getChannelName(), recipient, duration);
                    } else {
                        String errmsg = (String) result.getOrDefault("errmsg", "Unknown error");
                        return NotificationResult.failure(getChannelName(), recipient,
                                errmsg, request.getRetryCount());
                    }
                } else {
                    return NotificationResult.failure(getChannelName(), recipient,
                            "DingTalk API returned status: " + response.getStatus(), request.getRetryCount());
                }
            } catch (Exception e) {
                long duration = (System.nanoTime() - startTime) / 1_000_000;
                log.error("Failed to send DingTalk notification: {}", e.getMessage(), e);
                return NotificationResult.failure(getChannelName(), recipient,
                        e.getMessage(), request.getRetryCount());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> buildMarkdownPayload(NotificationRequest request) {
        Map<String, Object> markdown = new HashMap<>();
        markdown.put("title", request.getSubject());
        markdown.put("text", request.getContent());

        Map<String, Object> payload = new HashMap<>();
        payload.put("msgtype", "markdown");
        payload.put("markdown", markdown);

        Map<String, Object> at = new HashMap<>();
        at.put("isAtAll", false);
        if (request.getMetadata() != null && request.getMetadata().containsKey("atMobiles")) {
            @SuppressWarnings("unchecked")
            List<String> mobiles = (List<String>) request.getMetadata().get("atMobiles");
            at.put("atMobiles", mobiles);
        }
        payload.put("at", at);

        return payload;
    }

    private String generateSign(long timestamp, String secret) {
        String stringToSign = timestamp + "\n" + secret;
        byte[] hmacResult = SecureUtil.hmacSha256(secret.getBytes()).digest(stringToSign.getBytes());
        return java.util.Base64.getUrlEncoder().encodeToString(hmacResult).replaceAll("\\+", "%20")
                .replaceAll("/", "%2F").replaceAll("=", "%3D");
    }

    private boolean isValidDingTalkWebhook(String url) {
        return StrUtil.isNotBlank(url) && DINGTALK_WEBHOOK_PATTERN.matcher(url).matches();
    }
}
