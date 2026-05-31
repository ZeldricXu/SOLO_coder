package com.observability.alert.notification;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class DingTalkChannel implements NotificationChannel {

    @Override
    public String getType() {
        return "dingtalk";
    }

    @Override
    public void send(String title, String message, Map<String, Object> config) {
        String webhook = (String) config.get("webhook");
        if (webhook == null) {
            log.warn("DingTalk webhook not configured");
            return;
        }

        try {
            Map<String, Object> body = new HashMap<>();
            body.put("msgtype", "text");

            Map<String, Object> text = new HashMap<>();
            text.put("content", "【" + title + "】\n" + message);
            body.put("text", text);

            HttpResponse response = HttpRequest.post(webhook)
                    .header("Content-Type", "application/json")
                    .body(JSON.toJSONString(body))
                    .execute();

            if (!response.isOk()) {
                log.error("DingTalk notification failed: {}", response.body());
            }
        } catch (Exception e) {
            log.error("Failed to send DingTalk notification", e);
        }
    }
}
