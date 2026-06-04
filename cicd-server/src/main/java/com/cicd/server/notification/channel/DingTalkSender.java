package com.cicd.server.notification.channel;

import com.cicd.common.enums.NotificationChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class DingTalkSender implements NotificationSender {

    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${notification.dingtalk.webhook-url:}")
    private String defaultWebhookUrl;

    @Value("${notification.dingtalk.secret:}")
    private String defaultSecret;

    @Override
    public NotificationChannel getChannelType() {
        return NotificationChannel.DINGTALK;
    }

    @Override
    public boolean send(String target, String title, String content, Map<String, Object> extra) throws Exception {
        String webhookUrl = target != null && !target.isEmpty() ? target : defaultWebhookUrl;
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            log.warn("DingTalk webhook URL not configured");
            return false;
        }

        String secret = extra != null && extra.containsKey("secret") ? (String) extra.get("secret") : defaultSecret;
        if (secret != null && !secret.isEmpty()) {
            webhookUrl = signUrl(webhookUrl, secret);
        }

        Map<String, Object> message = buildMessage(title, content, extra);

        RequestBody body = RequestBody.create(
                objectMapper.writeValueAsString(message),
                MediaType.parse("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
                .url(webhookUrl)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);

            if (result.containsKey("errcode") && (Integer) result.get("errcode") == 0) {
                log.info("DingTalk notification sent successfully");
                return true;
            } else {
                log.error("DingTalk notification failed: {}", responseBody);
                return false;
            }
        }
    }

    private Map<String, Object> buildMessage(String title, String content, Map<String, Object> extra) {
        Map<String, Object> message = new HashMap<>();

        String msgType = extra != null && extra.containsKey("msgtype") ? (String) extra.get("msgtype") : "markdown";

        if ("action_card".equals(msgType) && extra != null && extra.containsKey("actions")) {
            message.put("msgtype", "action_card");
            Map<String, Object> actionCard = new HashMap<>();
            actionCard.put("title", title);
            actionCard.put("markdown", content);
            actionCard.put("btn_orientation", "1");
            actionCard.put("btns", extra.get("actions"));
            message.put("action_card", actionCard);
        } else if ("markdown".equals(msgType)) {
            message.put("msgtype", "markdown");
            Map<String, Object> markdown = new HashMap<>();
            markdown.put("title", title);
            markdown.put("text", "### " + title + "\n\n" + content);
            message.put("markdown", markdown);
        } else {
            message.put("msgtype", "text");
            Map<String, Object> text = new HashMap<>();
            text.put("content", title + "\n" + content);
            message.put("text", text);
        }

        if (extra != null && extra.containsKey("atMobiles")) {
            Map<String, Object> at = new HashMap<>();
            at.put("atMobiles", extra.get("atMobiles"));
            at.put("isAtAll", extra.getOrDefault("isAtAll", false));
            message.put("at", at);
        }

        return message;
    }

    private String signUrl(String url, String secret) throws Exception {
        Long timestamp = System.currentTimeMillis();
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        String sign = URLEncoder.encode(Base64.getEncoder().encodeToString(signData), "UTF-8");
        return url + "&timestamp=" + timestamp + "&sign=" + sign;
    }
}
