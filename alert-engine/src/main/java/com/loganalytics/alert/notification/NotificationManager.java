package com.loganalytics.alert.notification;

import com.loganalytics.alert.config.AlertEngineConfig;
import com.loganalytics.common.model.Alert;
import com.loganalytics.common.model.AlertRule;
import com.loganalytics.common.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

public class NotificationManager {
    private static final Logger log = LoggerFactory.getLogger(NotificationManager.class);
    private final AlertEngineConfig config;
    private final HttpClient httpClient;

    public NotificationManager(AlertEngineConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getHttpTimeoutSeconds()))
                .build();
    }

    public void sendNotifications(Alert alert, AlertRule rule) {
        List<AlertRule.NotificationChannel> channels = rule.getNotificationChannels();
        if (channels == null || channels.isEmpty()) {
            return;
        }

        for (AlertRule.NotificationChannel channel : channels) {
            try {
                switch (channel) {
                    case EMAIL -> sendEmail(alert, rule);
                    case WEBHOOK -> sendWebhook(alert, rule);
                    case SLACK -> sendSlack(alert, rule);
                    case PAGERDUTY -> sendPagerDuty(alert, rule);
                    case SMS -> sendSms(alert, rule);
                }
            } catch (Exception e) {
                log.error("Failed to send notification via {} for alert {}", channel, alert.getId(), e);
            }
        }
    }

    private void sendEmail(Alert alert, AlertRule rule) throws MessagingException {
        if (config.getSmtpHost() == null) {
            log.warn("SMTP not configured, skipping email notification");
            return;
        }

        Properties props = new Properties();
        props.put("mail.smtp.host", config.getSmtpHost());
        props.put("mail.smtp.port", config.getSmtpPort());
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");

        Session session = Session.getInstance(props, new Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(config.getSmtpUsername(), config.getSmtpPassword());
            }
        });

        Message message = new MimeMessage(session);
        message.setFrom(new InternetAddress(config.getSmtpUsername()));
        message.setRecipients(Message.RecipientType.TO,
                InternetAddress.parse(String.join(",", rule.getNotificationTargets())));
        message.setSubject(String.format("[%s] %s - %s", alert.getSeverity(), alert.getStatus(), alert.getSummary()));
        message.setText(buildEmailBody(alert, rule));

        Transport.send(message);
        log.info("Email notification sent for alert {}", alert.getId());
    }

    private void sendWebhook(Alert alert, AlertRule rule) throws Exception {
        List<String> targets = rule.getNotificationTargets();
        if (targets == null || targets.isEmpty()) {
            return;
        }

        Map<String, Object> payload = buildNotificationPayload(alert, rule);
        String jsonBody = JsonUtils.toJson(payload);

        for (String url : targets) {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(config.getHttpTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

            if (rule.getWebhookHeaders() != null) {
                rule.getWebhookHeaders().forEach(requestBuilder::header);
            }

            HttpResponse<String> response = httpClient.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());
            log.info("Webhook notification sent to {} for alert {}, response: {}",
                    url, alert.getId(), response.statusCode());
        }
    }

    private void sendSlack(Alert alert, AlertRule rule) throws Exception {
        String webhookUrl = rule.getNotificationTargets() != null && !rule.getNotificationTargets().isEmpty()
                ? rule.getNotificationTargets().get(0)
                : config.getSlackWebhookUrl();

        if (webhookUrl == null) {
            log.warn("Slack webhook not configured, skipping Slack notification");
            return;
        }

        Map<String, Object> slackMessage = new HashMap<>();
        slackMessage.put("username", "Log Analytics Alert");
        slackMessage.put("icon_emoji", getSlackIcon(alert.getSeverity()));

        Map<String, String> attachment = new HashMap<>();
        attachment.put("color", getSlackColor(alert.getSeverity()));
        attachment.put("title", String.format("[%s] %s", alert.getStatus(), alert.getSummary()));
        attachment.put("text", buildSlackBody(alert, rule));
        attachment.put("footer", "Log Analytics Platform");

        slackMessage.put("attachments", List.of(attachment));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .timeout(Duration.ofSeconds(config.getHttpTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.toJson(slackMessage)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.info("Slack notification sent for alert {}, response: {}", alert.getId(), response.statusCode());
    }

    private void sendPagerDuty(Alert alert, AlertRule rule) throws Exception {
        String apiKey = config.getPagerdutyApiKey();
        if (apiKey == null) {
            log.warn("PagerDuty API key not configured, skipping PagerDuty notification");
            return;
        }

        Map<String, Object> event = new HashMap<>();
        event.put("routing_key", rule.getNotificationTargets() != null && !rule.getNotificationTargets().isEmpty()
                ? rule.getNotificationTargets().get(0) : apiKey);
        event.put("event_action", alert.getStatus() == Alert.AlertStatus.RESOLVED ? "resolve" : "trigger");
        event.put("dedup_key", alert.getId());

        Map<String, Object> payload = new HashMap<>();
        payload.put("summary", alert.getSummary());
        payload.put("source", alert.getServiceName() != null ? alert.getServiceName() : "log-analytics");
        payload.put("severity", getPagerDutySeverity(alert.getSeverity()));
        payload.put("custom_details", buildPagerDutyDetails(alert, rule));

        event.put("payload", payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://events.pagerduty.com/v2/enqueue"))
                .timeout(Duration.ofSeconds(config.getHttpTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.toJson(event)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.info("PagerDuty notification sent for alert {}, response: {}", alert.getId(), response.statusCode());
    }

    private void sendSms(Alert alert, AlertRule rule) {
        log.warn("SMS notification not implemented yet for alert {}", alert.getId());
    }

    private Map<String, Object> buildNotificationPayload(Alert alert, AlertRule rule) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("alertId", alert.getId());
        payload.put("ruleId", alert.getRuleId());
        payload.put("ruleName", alert.getRuleName());
        payload.put("status", alert.getStatus().name());
        payload.put("severity", alert.getSeverity().name());
        payload.put("serviceName", alert.getServiceName());
        payload.put("summary", alert.getSummary());
        payload.put("description", alert.getDescription());
        payload.put("labels", alert.getLabels());
        payload.put("escalationLevel", alert.getEscalationLevel());
        payload.put("createdAt", alert.getCreatedAt().toString());
        payload.put("notificationCount", alert.getNotificationCount());
        return payload;
    }

    private String buildEmailBody(Alert alert, AlertRule rule) {
        StringBuilder sb = new StringBuilder();
        sb.append("Alert Details:\n\n");
        sb.append("Status: ").append(alert.getStatus()).append("\n");
        sb.append("Severity: ").append(alert.getSeverity()).append("\n");
        sb.append("Service: ").append(alert.getServiceName()).append("\n");
        sb.append("Rule: ").append(alert.getRuleName()).append("\n");
        sb.append("Escalation Level: ").append(alert.getEscalationLevel()).append("\n");
        sb.append("Created At: ").append(alert.getCreatedAt()).append("\n\n");
        sb.append("Summary: ").append(alert.getSummary()).append("\n\n");
        sb.append("Description: ").append(alert.getDescription()).append("\n\n");
        if (alert.getLabels() != null && !alert.getLabels().isEmpty()) {
            sb.append("Labels:\n");
            alert.getLabels().forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append("\n"));
        }
        return sb.toString();
    }

    private String buildSlackBody(Alert alert, AlertRule rule) {
        StringBuilder sb = new StringBuilder();
        sb.append("*Service:* ").append(alert.getServiceName()).append("\n");
        sb.append("*Severity:* ").append(alert.getSeverity()).append("\n");
        sb.append("*Rule:* ").append(alert.getRuleName()).append("\n");
        sb.append("*Escalation Level:* ").append(alert.getEscalationLevel()).append("\n");
        sb.append("*Description:* ").append(alert.getDescription());
        return sb.toString();
    }

    private Map<String, Object> buildPagerDutyDetails(Alert alert, AlertRule rule) {
        Map<String, Object> details = new HashMap<>();
        details.put("serviceName", alert.getServiceName());
        details.put("ruleName", alert.getRuleName());
        details.put("description", alert.getDescription());
        details.put("escalationLevel", alert.getEscalationLevel());
        details.put("labels", alert.getLabels());
        return details;
    }

    private String getSlackIcon(com.loganalytics.common.model.AnomalyEvent.Severity severity) {
        return switch (severity) {
            case CRITICAL -> ":rotating_light:";
            case HIGH -> ":red_circle:";
            case MEDIUM -> ":orange_circle:";
            case LOW -> ":yellow_circle:";
        };
    }

    private String getSlackColor(com.loganalytics.common.model.AnomalyEvent.Severity severity) {
        return switch (severity) {
            case CRITICAL, HIGH -> "danger";
            case MEDIUM -> "warning";
            case LOW -> "good";
        };
    }

    private String getPagerDutySeverity(com.loganalytics.common.model.AnomalyEvent.Severity severity) {
        return switch (severity) {
            case CRITICAL -> "critical";
            case HIGH -> "error";
            case MEDIUM -> "warning";
            case LOW -> "info";
        };
    }
}
