package com.iotconnect.service;

import com.iotconnect.entity.AlertEvent;
import com.iotconnect.entity.AlertRule;
import com.iotconnect.enums.AlertSeverity;
import com.iotconnect.repository.AlertRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class NotificationService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    private final AlertRuleRepository alertRuleRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${alert.notification.retry-count:3}")
    private int maxRetryCount;

    @Value("${alert.notification.retry-interval-seconds:60}")
    private int retryIntervalSeconds;

    public NotificationService(AlertRuleRepository alertRuleRepository,
                                KafkaTemplate<String, String> kafkaTemplate) {
        this.alertRuleRepository = alertRuleRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Async
    public void sendAlertNotification(AlertEvent alertEvent) {
        Optional<AlertRule> ruleOpt = alertRuleRepository.findById(alertEvent.getRuleId());
        
        if (ruleOpt.isEmpty()) {
            logger.warn("Cannot send notification: alert rule not found for ruleId={}", alertEvent.getRuleId());
            return;
        }

        AlertRule rule = ruleOpt.get();
        List<String> channels = rule.getNotifyChannels();

        if (channels == null || channels.isEmpty()) {
            logger.info("No notification channels configured for ruleId={}", rule.getRuleId());
            return;
        }

        String notificationMessage = buildAlertNotificationMessage(alertEvent, rule);

        for (String channel : channels) {
            try {
                sendToChannel(channel, notificationMessage, alertEvent);
                logger.info("Notification sent via {}: deviceId={}, alertId={}",
                        channel, alertEvent.getDeviceId(), alertEvent.getAlertId());
            } catch (Exception e) {
                logger.error("Failed to send notification via {}: alertId={}, error={}",
                        channel, alertEvent.getAlertId(), e.getMessage());
            }
        }
    }

    @Async
    public void sendRecoveryNotification(AlertEvent alertEvent) {
        Optional<AlertRule> ruleOpt = alertRuleRepository.findById(alertEvent.getRuleId());
        
        if (ruleOpt.isEmpty()) {
            return;
        }

        AlertRule rule = ruleOpt.get();
        List<String> channels = rule.getNotifyChannels();

        if (channels == null || channels.isEmpty()) {
            return;
        }

        String recoveryMessage = buildRecoveryNotificationMessage(alertEvent, rule);

        for (String channel : channels) {
            try {
                sendToChannel(channel, recoveryMessage, alertEvent);
                logger.info("Recovery notification sent via {}: deviceId={}, alertId={}",
                        channel, alertEvent.getDeviceId(), alertEvent.getAlertId());
            } catch (Exception e) {
                logger.error("Failed to send recovery notification via {}: alertId={}, error={}",
                        channel, alertEvent.getAlertId(), e.getMessage());
            }
        }
    }

    private String buildAlertNotificationMessage(AlertEvent alertEvent, AlertRule rule) {
        AlertSeverity severity = AlertSeverity.fromValue(alertEvent.getSeverity());
        
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(severity.getValue().toUpperCase()).append(" ALERT] ");
        sb.append("设备告警触发\n");
        sb.append("设备ID: ").append(alertEvent.getDeviceId()).append("\n");
        sb.append("告警规则: ").append(rule.getRuleName()).append("\n");
        sb.append("告警指标: ").append(rule.getMetric()).append("\n");
        sb.append("当前值: ").append(alertEvent.getMetricValue()).append("\n");
        sb.append("阈值: ").append(alertEvent.getThreshold()).append("\n");
        sb.append("告警等级: ").append(severity.getValue()).append("\n");
        sb.append("触发时间: ").append(alertEvent.getTriggeredAt()).append("\n");
        
        if (alertEvent.getDescription() != null) {
            sb.append("描述: ").append(alertEvent.getDescription()).append("\n");
        }
        
        if (alertEvent.getAggregationCount() > 1) {
            sb.append("聚合次数: ").append(alertEvent.getAggregationCount()).append("\n");
        }

        return sb.toString();
    }

    private String buildRecoveryNotificationMessage(AlertEvent alertEvent, AlertRule rule) {
        StringBuilder sb = new StringBuilder();
        sb.append("[RECOVERY] 设备告警恢复\n");
        sb.append("设备ID: ").append(alertEvent.getDeviceId()).append("\n");
        sb.append("告警规则: ").append(rule.getRuleName()).append("\n");
        sb.append("告警指标: ").append(rule.getMetric()).append("\n");
        sb.append("触发时间: ").append(alertEvent.getTriggeredAt()).append("\n");
        sb.append("恢复时间: ").append(alertEvent.getResolvedAt()).append("\n");

        return sb.toString();
    }

    private void sendToChannel(String channel, String message, AlertEvent alertEvent) {
        switch (channel.toLowerCase()) {
            case "sms":
                sendSms(message, alertEvent);
                break;
            case "email":
                sendEmail(message, alertEvent);
                break;
            case "webhook":
                sendWebhook(message, alertEvent);
                break;
            case "kafka":
                sendKafka(message, alertEvent);
                break;
            default:
                logger.warn("Unknown notification channel: {}", channel);
        }
    }

    private void sendSms(String message, AlertEvent alertEvent) {
        logger.info("SMS notification (simulated): deviceId={}, alertId={}",
                alertEvent.getDeviceId(), alertEvent.getAlertId());
        logger.debug("SMS message content: {}", message);
        
        publishNotificationEvent("sms", message, alertEvent);
    }

    private void sendEmail(String message, AlertEvent alertEvent) {
        logger.info("Email notification (simulated): deviceId={}, alertId={}",
                alertEvent.getDeviceId(), alertEvent.getAlertId());
        logger.debug("Email message content: {}", message);
        
        publishNotificationEvent("email", message, alertEvent);
    }

    private void sendWebhook(String message, AlertEvent alertEvent) {
        logger.info("Webhook notification (simulated): deviceId={}, alertId={}",
                alertEvent.getDeviceId(), alertEvent.getAlertId());
        logger.debug("Webhook message content: {}", message);
        
        publishNotificationEvent("webhook", message, alertEvent);
    }

    private void sendKafka(String message, AlertEvent alertEvent) {
        String kafkaMessage = String.format(
                "{\"alert_id\":\"%s\",\"device_id\":\"%s\",\"severity\":\"%s\",\"message\":\"%s\"}",
                alertEvent.getAlertId(),
                alertEvent.getDeviceId(),
                alertEvent.getSeverity(),
                escapeJson(message)
        );

        try {
            kafkaTemplate.send("iot-alert-notifications", alertEvent.getDeviceId(), kafkaMessage);
            logger.info("Kafka notification sent: alertId={}", alertEvent.getAlertId());
        } catch (Exception e) {
            logger.error("Failed to send Kafka notification: {}", e.getMessage());
        }
    }

    private void publishNotificationEvent(String channel, String message, AlertEvent alertEvent) {
        String eventMessage = String.format(
                "{\"timestamp\":\"%s\",\"channel\":\"%s\",\"alert_id\":\"%s\",\"device_id\":\"%s\",\"severity\":\"%s\",\"status\":\"%s\"}",
                java.time.Instant.now().toString(),
                channel,
                alertEvent.getAlertId(),
                alertEvent.getDeviceId(),
                alertEvent.getSeverity(),
                AlertEvent.class.getSimpleName()
        );

        try {
            kafkaTemplate.send("iot-notification-events", alertEvent.getDeviceId(), eventMessage);
        } catch (Exception e) {
            logger.warn("Failed to publish notification event: {}", e.getMessage());
        }
    }

    private String escapeJson(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }

    @Async
    public void sendCustomNotification(String deviceId, String title, String message, List<String> channels) {
        if (channels == null || channels.isEmpty()) {
            logger.warn("No channels specified for custom notification");
            return;
        }

        String customMessage = String.format("[%s]\n设备ID: %s\n%s", title, deviceId, message);

        for (String channel : channels) {
            try {
                logger.info("Custom notification sent via {}: deviceId={}, title={}", channel, deviceId, title);
                logger.debug("Custom notification message: {}", customMessage);
            } catch (Exception e) {
                logger.error("Failed to send custom notification via {}: deviceId={}, error={}",
                        channel, deviceId, e.getMessage());
            }
        }
    }
}
