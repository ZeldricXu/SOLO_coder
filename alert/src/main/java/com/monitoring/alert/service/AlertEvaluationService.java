package com.monitoring.alert.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.monitoring.alert.model.AlertEvent;
import com.monitoring.alert.model.AlertRule;
import com.monitoring.alert.notification.NotificationChannel;
import com.monitoring.alert.parser.AlertRuleParser;
import com.monitoring.common.utils.IdGenerator;
import com.monitoring.common.utils.JsonUtils;
import com.monitoring.dal.repository.AlertHistoryRepository;
import com.monitoring.dal.repository.AlertRuleRepository;
import com.monitoring.persistence.entity.AlertHistoryDO;
import com.monitoring.persistence.entity.AlertRuleDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlertEvaluationService {

    private final AlertRuleParser ruleParser;
    private final AlertRuleRepository alertRuleRepository;
    private final AlertHistoryRepository alertHistoryRepository;
    private final Map<String, NotificationChannel> notificationChannels = new ConcurrentHashMap<>();

    private final Cache<String, AlertState> alertStates = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(24))
            .maximumSize(10000)
            .build();

    public void registerNotificationChannel(NotificationChannel channel) {
        notificationChannels.put(channel.getName(), channel);
    }

    public Mono<AlertRule> createRule(Map<String, Object> ruleData) {
        AlertRule rule = ruleParser.parse(ruleData);
        if (rule.getRuleId() == null) {
            rule.setRuleId("rule_" + IdGenerator.generateShortId());
        }

        AlertRuleDO ruleDO = toDO(rule);
        alertRuleRepository.save(ruleDO);

        log.info("Created alert rule: id={}, name={}", rule.getRuleId(), rule.getName());
        return Mono.just(rule);
    }

    public Mono<List<AlertRule>> getAllRules() {
        return Mono.fromSupplier(() ->
                alertRuleRepository.findAllEnabled().stream()
                        .map(this::fromDO)
                        .toList()
        );
    }

    public Mono<Void> evaluateAll(Map<String, Double> currentMetrics) {
        return getAllRules()
                .flatMapMany(Flux::fromIterable)
                .flatMap(rule -> evaluateRule(rule, currentMetrics))
                .then();
    }

    public Mono<AlertEvent> evaluateRule(AlertRule rule, Map<String, Double> currentMetrics) {
        Double metricValue = currentMetrics.get(rule.getMetricName());
        if (metricValue == null) {
            return Mono.empty();
        }

        boolean conditionMet = ruleParser.evaluate(metricValue, rule.getOperator(), rule.getThreshold());

        String alertKey = rule.getRuleId() + ":" + rule.getMetricName();
        AlertState state = alertStates.get(alertKey, k -> new AlertState());

        if (conditionMet) {
            state.violationCount++;
            state.lastViolationTime = Instant.now();

            if (state.violationCount * 15 >= rule.getDurationSeconds() && !state.firing) {
                return fireAlert(rule, metricValue, alertKey, state);
            }
        } else {
            if (state.firing && state.lastViolationTime != null) {
                Duration sinceLastViolation = Duration.between(state.lastViolationTime, Instant.now());
                if (sinceLastViolation.getSeconds() > rule.getDurationSeconds() * 2) {
                    return resolveAlert(rule, alertKey, state);
                }
            }
            state.violationCount = Math.max(0, state.violationCount - 1);
        }

        return Mono.empty();
    }

    private Mono<AlertEvent> fireAlert(AlertRule rule, Double currentValue, String alertKey, AlertState state) {
        AlertEvent alert = AlertEvent.builder()
                .alertId("alert_" + IdGenerator.generateShortId())
                .ruleId(rule.getRuleId())
                .severity(rule.getSeverity())
                .status("firing")
                .currentValue(currentValue)
                .message(String.format("Alert %s: %s %s %.2f (current: %.2f)",
                        rule.getName(), rule.getMetricName(), rule.getOperator(),
                        rule.getThreshold(), currentValue))
                .labels(rule.getLabels())
                .annotations(rule.getAnnotations())
                .startedAt(Instant.now())
                .build();

        state.firing = true;
        state.alertId = alert.getAlertId();
        alertStates.put(alertKey, state);

        AlertHistoryDO historyDO = AlertHistoryDO.builder()
                .alertId(alert.getAlertId())
                .ruleId(alert.getRuleId())
                .severity(alert.getSeverity())
                .status("firing")
                .currentValue(currentValue)
                .message(alert.getMessage())
                .labels(JsonUtils.toJson(alert.getLabels()))
                .annotations(JsonUtils.toJson(alert.getAnnotations()))
                .startedAt(alert.getStartedAt())
                .createdAt(Instant.now())
                .build();
        alertHistoryRepository.save(historyDO);

        log.warn("Alert fired: id={}, rule={}, value={}", alert.getAlertId(), rule.getName(), currentValue);

        return sendNotifications(alert).thenReturn(alert);
    }

    private Mono<AlertEvent> resolveAlert(AlertRule rule, String alertKey, AlertState state) {
        AlertEvent alert = AlertEvent.builder()
                .alertId(state.alertId)
                .ruleId(rule.getRuleId())
                .severity(rule.getSeverity())
                .status("resolved")
                .message("Alert resolved: " + rule.getName())
                .labels(rule.getLabels())
                .annotations(rule.getAnnotations())
                .startedAt(state.lastViolationTime)
                .resolvedAt(Instant.now())
                .build();

        state.firing = false;
        state.violationCount = 0;
        alertStates.put(alertKey, state);

        if (state.alertId != null) {
            alertHistoryRepository.resolveAlert(state.alertId, Instant.now());
        }

        log.info("Alert resolved: id={}, rule={}", alert.getAlertId(), rule.getName());

        return sendNotifications(alert).thenReturn(alert);
    }

    private Mono<Void> sendNotifications(AlertEvent alert) {
        return Flux.fromIterable(notificationChannels.values())
                .flatMap(channel -> channel.send(alert)
                        .onErrorResume(e -> {
                            log.error("Failed to send notification via {}: {}", channel.getName(), e.getMessage());
                            return Mono.empty();
                        }))
                .then();
    }

    public Mono<List<AlertEvent>> getActiveAlerts() {
        return Mono.fromSupplier(() ->
                alertHistoryRepository.findActiveAlerts().stream()
                        .map(this::historyFromDO)
                        .toList()
        );
    }

    public void deleteRule(String ruleId) {
        alertRuleRepository.deleteByRuleId(ruleId);
        log.info("Deleted alert rule: {}", ruleId);
    }

    private AlertRuleDO toDO(AlertRule rule) {
        return AlertRuleDO.builder()
                .ruleId(rule.getRuleId())
                .name(rule.getName())
                .description(rule.getDescription())
                .namespace(rule.getNamespace())
                .metricName(rule.getMetricName())
                .operator(rule.getOperator())
                .threshold(rule.getThreshold())
                .durationSeconds(rule.getDurationSeconds())
                .severity(rule.getSeverity())
                .notificationChannels(rule.getNotificationChannels() != null ?
                        String.join(",", rule.getNotificationChannels()) : null)
                .labels(JsonUtils.toJson(rule.getLabels()))
                .annotations(JsonUtils.toJson(rule.getAnnotations()))
                .enabled(rule.getEnabled())
                .createdAt(rule.getCreatedAt())
                .updatedAt(rule.getUpdatedAt())
                .build();
    }

    private AlertRule fromDO(AlertRuleDO ruleDO) {
        return AlertRule.builder()
                .ruleId(ruleDO.getRuleId())
                .name(ruleDO.getName())
                .description(ruleDO.getDescription())
                .namespace(ruleDO.getNamespace())
                .metricName(ruleDO.getMetricName())
                .operator(ruleDO.getOperator())
                .threshold(ruleDO.getThreshold())
                .durationSeconds(ruleDO.getDurationSeconds())
                .severity(ruleDO.getSeverity())
                .notificationChannels(ruleDO.getNotificationChannels() != null ?
                        Arrays.asList(ruleDO.getNotificationChannels().split(",")) : Collections.emptyList())
                .labels(JsonUtils.fromJson(ruleDO.getLabels(), Map.class))
                .annotations(JsonUtils.fromJson(ruleDO.getAnnotations(), Map.class))
                .enabled(ruleDO.getEnabled())
                .createdAt(ruleDO.getCreatedAt())
                .updatedAt(ruleDO.getUpdatedAt())
                .build();
    }

    private AlertEvent historyFromDO(AlertHistoryDO historyDO) {
        return AlertEvent.builder()
                .alertId(historyDO.getAlertId())
                .ruleId(historyDO.getRuleId())
                .severity(historyDO.getSeverity())
                .status(historyDO.getStatus())
                .currentValue(historyDO.getCurrentValue())
                .message(historyDO.getMessage())
                .labels(JsonUtils.fromJson(historyDO.getLabels(), Map.class))
                .annotations(JsonUtils.fromJson(historyDO.getAnnotations(), Map.class))
                .startedAt(historyDO.getStartedAt())
                .resolvedAt(historyDO.getResolvedAt())
                .build();
    }

    private static class AlertState {
        int violationCount;
        boolean firing;
        String alertId;
        Instant lastViolationTime;
    }
}
