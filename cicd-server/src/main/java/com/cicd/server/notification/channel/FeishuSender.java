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
public class FeishuSender implements NotificationSender {

    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${notification.feishu.webhook-url:}")
    private String defaultWebhookUrl;

    @Value("${notification.feishu.secret:}")
    private String defaultSecret;

    @Override
    public NotificationChannel getChannelType() {
        return NotificationChannel.FEISHU;
    }

    @Override
    public boolean send(String target, String title, String content, Map<String, Object> extra) throws Exception {
        String webhookUrl = target != null && !target.isEmpty() ? target : defaultWebhookUrl;
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            log.warn("Feishu webhook URL not configured");
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

            if (result.containsKey("code") && (Integer) result.get("code") == 0) {
                log.info("Feishu notification sent successfully");
                return true;
            } else {
                log.error("Feishu notification failed: {}", responseBody);
                return false;
            }
        }
    }

    private Map<String, Object> buildMessage(String title, String content, Map<String, Object> extra) {
        Map<String, Object> message = new HashMap<>();
        String msgType = extra != null && extra.containsKey("msgtype") ? (String) extra.get("msgtype") : "interactive";

        if ("interactive".equals(msgType)) {
            message.put("msg_type", "interactive");
            Map<String, Object> card = new HashMap<>();

            Map<String, Object> header = new HashMap<>();
            Map<String, Object> titleText = new HashMap<>();
            titleText.put("content", title);
            titleText.put("tag", "plain_text");
            header.put("title", titleText);
            header.put("template", extra != null && extra.containsKey("template") ? extra.get("template") : "blue");
            card.put("header", header);

            List<Map<String, Object>> elements = new ArrayList<>();
            Map<String, Object> contentElement = new HashMap<>();
            contentElement.put("tag", "markdown");
            contentElement.put("content", content);
            elements.add(contentElement);

            if (extra != null && extra.containsKey("actions")) {
                Map<String, Object> actionElement = new HashMap<>();
                actionElement.put("tag", "action");
                List<Map<String, Object>> actions = new ArrayList<>();

                List<Map<String, Object>> actionList = (List<Map<String, Object>>) extra.get("actions");
                for (Map<String, Object> action : actionList) {
                    Map<String, Object> btn = new HashMap<>();
                    btn.put("tag", "button");
                    btn.put("text", Map.of("tag", "plain_text", "content", action.get("title")));
                    btn.put("type", action.getOrDefault("type", "primary"));
                    btn.put("url", action.get("url"));
                    actions.add(btn);
                }

                actionElement.put("actions", actions);
                elements.add(actionElement);
            }

            card.put("elements", elements);
            message.put("card", card);
        } else {
            message.put("msg_type", "post");
            Map<String, Object> contentMap = new HashMap<>();
            Map<String, Object> post = new HashMap<>();
            Map<String, Object> zhCn = new HashMap<>();
            zhCn.put("title", title);

            List<List<Map<String, Object>>> contents = new ArrayList<>();
            List<Map<String, Object>> line = new ArrayList<>();
            Map<String, Object> text = new HashMap<>();
            text.put("tag", "text");
            text.put("text", content);
            line.add(text);
            contents.add(line);

            zhCn.put("content", contents);
            post.put("zh_cn", zhCn);
            contentMap.put("post", post);
            message.put("content", contentMap);
        }

        return message;
    }
}
