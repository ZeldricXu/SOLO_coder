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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SlackNotifier extends AbstractNotifier implements NotificationChannel {

    private final CloseableHttpClient httpClient;

    public SlackNotifier(NotificationConfig config) {
        super(config);
        this.httpClient = HttpClients.createDefault();
    }

    public SlackNotifier(NotificationConfig config, TemplateEngine templateEngine) {
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

        Map<String, Object> payload = buildSlackPayload(alert);
        String jsonPayload = JsonUtils.toJson(payload);

        HttpPost post = new HttpPost(webhookUrl);
        post.setHeader("Content-Type", "application/json; charset=utf-8");
        post.setEntity(new StringEntity(jsonPayload, StandardCharsets.UTF_8));

        try (CloseableHttpResponse response = httpClient.execute(post)) {
            int statusCode = response.getStatusLine().getStatusCode();
            String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);

            if (statusCode == 200) {
                logger.info("Slack notification sent successfully");
                return true;
            } else {
                logger.error("Slack webhook failed with status {}: {}", statusCode, responseBody);
                return false;
            }
        }
    }

    private Map<String, Object> buildSlackPayload(AlertEvent alert) {
        Map<String, Object> payload = new HashMap<>();

        String color = getColorForSeverity(alert.getSeverity());

        List<Map<String, Object>> attachments = new ArrayList<>();
        Map<String, Object> attachment = new HashMap<>();

        attachment.put("color", color);
        attachment.put("pretext", getSeverityEmoji(alert.getSeverity()) + " *Alert Triggered*");
        attachment.put("title", alert.getRuleName());
        attachment.put("title_link", "#");
        attachment.put("text", alert.getDescription());
        attachment.put("mrkdwn_in", List.of("pretext", "text", "fields"));

        List<Map<String, Object>> fields = new ArrayList<>();

        Map<String, Object> severityField = new HashMap<>();
        severityField.put("title", "Severity");
        severityField.put("value", alert.getSeverity() != null ? alert.getSeverity().name() : "UNKNOWN");
        severityField.put("short", true);
        fields.add(severityField);

        Map<String, Object> durationField = new HashMap<>();
        durationField.put("title", "Duration");
        durationField.put("value", alert.getDurationMinutes() + " min");
        durationField.put("short", true);
        fields.add(durationField);

        if (alert.getDetails() != null && !alert.getDetails().isEmpty()) {
            StringBuilder detailsText = new StringBuilder();
            for (String detail : alert.getDetails()) {
                detailsText.append("• ").append(detail).append("\n");
            }
            Map<String, Object> detailsField = new HashMap<>();
            detailsField.put("title", "Details");
            detailsField.put("value", detailsText.toString());
            detailsField.put("short", false);
            fields.add(detailsField);
        }

        attachment.put("fields", fields);
        attachments.add(attachment);

        payload.put("attachments", attachments);
        payload.put("text", getSeverityEmoji(alert.getSeverity()) + " *" + alert.getRuleName() + "* - " + alert.getDescription());

        return payload;
    }

    private String getColorForSeverity(Enum<?> severity) {
        if (severity == null) return "#808080";
        switch (severity.name()) {
            case "CRITICAL": return "#DC143C";
            case "ERROR": return "#FF6347";
            case "WARNING": return "#FFA500";
            case "INFO": return "#1E90FF";
            default: return "#808080";
        }
    }

    private String getSeverityEmoji(Enum<?> severity) {
        if (severity == null) return "⚪";
        switch (severity.name()) {
            case "CRITICAL": return "🔴";
            case "ERROR": return "🟠";
            case "WARNING": return "🟡";
            case "INFO": return "🔵";
            default: return "⚪";
        }
    }

    @Override
    public String getName() {
        return config.getName() != null ? config.getName() : "slack";
    }
}
