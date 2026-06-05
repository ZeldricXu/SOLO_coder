package com.datateam.loganalyzer.notification;

import com.datateam.loganalyzer.model.AlertEvent;
import com.datateam.loganalyzer.model.NotificationConfig;
import com.datateam.loganalyzer.util.JsonUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class WeChatWorkNotifier implements NotificationChannel {

    private static final Logger logger = LoggerFactory.getLogger(WeChatWorkNotifier.class);

    private final NotificationConfig config;
    private final AlertTemplateEngine templateEngine;
    private final CloseableHttpClient httpClient;

    public WeChatWorkNotifier(NotificationConfig config) {
        this.config = config;
        this.templateEngine = new AlertTemplateEngine();
        this.httpClient = HttpClients.createDefault();
    }

    @Override
    public boolean send(AlertEvent alert) {
        if (!config.isEnabled()) {
            logger.warn("WeChat Work channel is disabled, skipping notification");
            return false;
        }

        try {
            String webhookUrl = config.getWebhookUrl();
            if (webhookUrl == null || webhookUrl.isEmpty()) {
                logger.error("Webhook URL is not configured");
                return false;
            }

            if (config.getSecret() != null && !config.getSecret().isEmpty()) {
                long timestamp = System.currentTimeMillis() / 1000;
                String sign = generateSignature(timestamp, config.getSecret());
                webhookUrl = webhookUrl + "&timestamp=" + timestamp + "&sign=" + sign;
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("msgtype", "markdown");

            Map<String, String> markdown = new HashMap<>();
            markdown.put("content", templateEngine.getWeChatWorkMarkdown(alert));
            payload.put("markdown", markdown);

            String jsonPayload = JsonUtils.toJson(payload);

            HttpPost post = new HttpPost(webhookUrl);
            post.setHeader("Content-Type", "application/json; charset=utf-8");
            post.setEntity(new StringEntity(jsonPayload, StandardCharsets.UTF_8));

            try (CloseableHttpResponse response = httpClient.execute(post)) {
                int statusCode = response.getStatusLine().getStatusCode();
                String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);

                if (statusCode == 200) {
                    Map<String, Object> resp = JsonUtils.fromJson(responseBody, Map.class);
                    Object errcode = resp.get("errcode");
                    if (errcode != null && ((Number) errcode).intValue() == 0) {
                        logger.info("WeChat Work notification sent successfully");
                        return true;
                    } else {
                        logger.error("WeChat Work API returned error: {}", responseBody);
                        return false;
                    }
                } else {
                    logger.error("WeChat Work webhook failed with status {}: {}", statusCode, responseBody);
                    return false;
                }
            }

        } catch (Exception e) {
            logger.error("Failed to send WeChat Work notification", e);
            return false;
        }
    }

    private String generateSignature(long timestamp, String secret) throws Exception {
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().encodeToString(signData);
    }

    @Override
    public String getName() {
        return config.getName() != null ? config.getName() : "wechat-work";
    }

    @Override
    public NotificationConfig.ChannelType getType() {
        return NotificationConfig.ChannelType.WECHAT_WORK;
    }

    @Override
    public boolean isEnabled() {
        return config.isEnabled();
    }

    @Override
    public NotificationConfig getConfig() {
        return config;
    }
}
