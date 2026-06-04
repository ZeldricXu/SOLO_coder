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
public class SlackSender implements NotificationSender {

    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${notification.slack.webhook-url:}")
    private String defaultWebhookUrl;

    @Override
    public NotificationChannel getChannelType() {
        return NotificationChannel.SLACK;
    }

    @Override
    public boolean send(String target, String title, String content, Map<String, Object> extra) throws Exception {
        String webhookUrl = target != null && !target.isEmpty() ? target : defaultWebhookUrl;
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            log.warn("Slack webhook URL not configured");
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
            int statusCode = response.code();
            if (statusCode == 200) {
                log.info("Slack notification sent successfully");
                return true;
            } else {
                String responseBody = response.body() != null ? response.body().string() : "";
                log.error("Slack notification failed with status {}: {}", statusCode, responseBody);
                return false;
            }
        }
    }

    private Map<String, Object> buildMessage(String title, String content, Map<String, Object> extra) {
        Map<String, Object> message = new HashMap<>();

        List<Map<String, Object>> blocks = new ArrayList<>();

        Map<String, Object> header = new HashMap<>();
        header.put("type", "header");
        Map<String, Object> headerText = new HashMap<>();
        headerText.put("type", "plain_text");
        headerText.put("text", title);
        header.put("text", headerText);
        blocks.add(header);

        Map<String, Object> section = new HashMap<>();
        section.put("type", "section");
        Map<String, Object> sectionText = new HashMap<>();
        sectionText.put("type", "mrkdwn");
        sectionText.put("text", content);
        section.put("text", sectionText);
        blocks.add(section);

        if (extra != null && extra.containsKey("actions")) {
            Map<String, Object> actionBlock = new HashMap<>();
            actionBlock.put("type", "actions");
            List<Map<String, Object>> elements = new ArrayList<>();

            List<Map<String, Object>> actionList = (List<Map<String, Object>>) extra.get("actions");
            for (Map<String, Object> action : actionList) {
                Map<String, Object> btn = new HashMap<>();
                btn.put("type", "button");
                btn.put("text", Map.of("type", "plain_text", "text", action.get("title")));
                btn.put("url", action.get("url"));
                btn.put("style", action.getOrDefault("style", "primary"));
                elements.add(btn);
            }

            actionBlock.put("elements", elements);
            blocks.add(actionBlock);
        }

        message.put("blocks", blocks);
        message.put("text", title + "\n" + content);

        return message;
    }
}
