package com.cicd.server.notification.channel;

import com.cicd.common.enums.NotificationChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class WecomSender implements NotificationSender {

    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${notification.wecom.webhook-url:}")
    private String defaultWebhookUrl;

    @Override
    public NotificationChannel getChannelType() {
        return NotificationChannel.WECOM;
    }

    @Override
    public boolean send(String target, String title, String content, Map<String, Object> extra) throws Exception {
        String webhookUrl = target != null && !target.isEmpty() ? target : defaultWebhookUrl;
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            log.warn("WeCom webhook URL not configured");
            return false;
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
                log.info("WeCom notification sent successfully");
                return true;
            } else {
                log.error("WeCom notification failed: {}", responseBody);
                return false;
            }
        }
    }

    private Map<String, Object> buildMessage(String title, String content, Map<String, Object> extra) {
        Map<String, Object> message = new HashMap<>();
        String msgType = extra != null && extra.containsKey("msgtype") ? (String) extra.get("msgtype") : "markdown";

        message.put("msgtype", msgType);

        if ("markdown".equals(msgType)) {
            Map<String, Object> markdown = new HashMap<>();
            markdown.put("content", "## " + title + "\n\n" + content);
            message.put("markdown", markdown);
        } else if ("news".equals(msgType)) {
            Map<String, Object> news = new HashMap<>();
            List<Map<String, Object>> articles = new ArrayList<>();
            Map<String, Object> article = new HashMap<>();
            article.put("title", title);
            article.put("description", content);
            article.put("url", extra != null ? (String) extra.getOrDefault("url", "") : "");
            articles.add(article);
            news.put("articles", articles);
            message.put("news", news);
        } else if ("text".equals(msgType)) {
            Map<String, Object> text = new HashMap<>();
            text.put("content", title + "\n" + content);
            message.put("text", text);
        }

        return message;
    }
}
