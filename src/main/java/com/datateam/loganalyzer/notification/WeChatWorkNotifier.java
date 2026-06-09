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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class WeChatWorkNotifier extends AbstractNotifier implements NotificationChannel {

    private final CloseableHttpClient httpClient;

    public WeChatWorkNotifier(NotificationConfig config) {
        super(config);
        this.httpClient = HttpClients.createDefault();
    }

    public WeChatWorkNotifier(NotificationConfig config, TemplateEngine templateEngine) {
        super(config, templateEngine);
        this.httpClient = HttpClients.createDefault();
    }

    @Override
    protected boolean doSend(AlertEvent alert) throws Exception {
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
}
