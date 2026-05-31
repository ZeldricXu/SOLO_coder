package com.observability.alert.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.observability.alert.entity.AlertRuleEntity;
import com.observability.alert.notification.NotificationChannel;
import com.observability.alert.parser.AlertExpressionParser;
import com.observability.common.exception.BusinessException;
import com.observability.common.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private final AlertExpressionParser expressionParser;
    private final List<NotificationChannel> notificationChannels;

    private final Map<String, Long> alertFiringSince = new HashMap<>();

    public Mono<AlertRuleEntity> createRule(String name, String metricName, String expression,
                                               String level, Double threshold, Integer duration,
                                               Map<String, Object> notificationConfig) {
        return Mono.fromCallable(() -> {
            AlertRuleEntity rule = new AlertRuleEntity();
            rule.setAlertId(IdGenerator.generateAlertId());
            rule.setName(name);
            rule.setMetricName(metricName);
            rule.setExpression(expression);
            rule.setLevel(level != null ? level : "warning");
            rule.setThreshold(threshold);
            rule.setDuration(duration != null ? duration : 60);
            rule.setNotificationConfig(notificationConfig);
            rule.setEnabled(true);

            log.info("Alert rule created - alertId: {}, name: {}", rule.getAlertId(), name);
            return rule;
        });
    }

    public Mono<Boolean> evaluateRule(String alertId, Map<String, Double> metrics) {
        return Mono.fromCallable(() -> {
            AlertRuleEntity rule = getRuleById(alertId);
            if (rule == null || !rule.getEnabled()) {
                return false;
            }

            boolean triggered = expressionParser.evaluate(rule.getExpression(), metrics);
            long now = System.currentTimeMillis();

            if (triggered) {
                alertFiringSince.putIfAbsent(alertId, now);
                long firingDuration = now - alertFiringSince.get(alertId);

                if (firingDuration >= rule.getDuration() * 1000L) {
                    sendNotification(rule, metrics);
                    return true;
                }
            } else {
                alertFiringSince.remove(alertId);
            }

            return false;
        });
    }

    public Mono<Map<String, Boolean>> evaluateAllRules(Map<String, Double> metrics) {
        return Mono.fromCallable(() -> {
            Map<String, Boolean> results = new HashMap<>();
            return results;
        });
    }

    public Mono<Void> deleteRule(String alertId) {
        return Mono.fromRunnable(() -> {
            alertFiringSince.remove(alertId);
            log.info("Alert rule deleted - alertId: {}", alertId);
        });
    }

    public Mono<List<AlertRuleEntity>> listRules() {
        return Mono.fromCallable(() -> {
            return List.of();
        });
    }

    private AlertRuleEntity getRuleById(String alertId) {
        return null;
    }

    private void sendNotification(AlertRuleEntity rule, Map<String, Double> metrics) {
        if (rule.getNotificationConfig() == null) {
            return;
        }

        String title = "【" + rule.getLevel().toUpperCase() + "】" + rule.getName();
        StringBuilder message = new StringBuilder();
        message.append("告警规则: ").append(rule.getName()).append("\n");
        message.append("指标: ").append(rule.getMetricName()).append("\n");
        message.append("表达式: ").append(rule.getExpression()).append("\n");
        message.append("当前指标值:\n");
        metrics.forEach((k, v) -> message.append("  ").append(k).append(": ").append(v).append("\n"));

        for (NotificationChannel channel : notificationChannels) {
            try {
                Object config = rule.getNotificationConfig().get(channel.getType());
                if (config != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> channelConfig = (Map<String, Object>) config;
                    channel.send(title, message.toString(), channelConfig);
                }
            } catch (Exception e) {
                log.error("Failed to send notification via channel: {}", channel.getType(), e);
            }
        }
    }
}
