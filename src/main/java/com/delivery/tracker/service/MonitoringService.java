package com.delivery.tracker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.delivery.tracker.entity.AlertRule;
import com.delivery.tracker.entity.MetricSnapshot;
import com.delivery.tracker.mapper.AlertRuleMapper;
import com.delivery.tracker.mapper.MetricSnapshotMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class MonitoringService {

    private final MetricSnapshotMapper metricSnapshotMapper;
    private final AlertRuleMapper alertRuleMapper;
    private final NotificationService notificationService;

    private final Map<String, BigDecimal> currentMetrics = new ConcurrentHashMap<>();

    public Mono<MetricSnapshot> recordMetric(String metricName, BigDecimal value, Map<String, String> dimensions) {
        return Mono.fromCallable(() -> {
            currentMetrics.put(metricName, value);

            MetricSnapshot snapshot = new MetricSnapshot();
            snapshot.setSnapshotId("snap_" + UUID.randomUUID().toString().substring(0, 8));
            snapshot.setTimestamp(LocalDateTime.now());
            snapshot.setMetrics(Map.of(metricName, value));
            snapshot.setDimensions(dimensions);
            metricSnapshotMapper.insert(snapshot);

            evaluateAlertRules(metricName, value);

            return snapshot;
        });
    }

    public Mono<Map<String, BigDecimal>> getCurrentMetrics() {
        return Mono.just(new ConcurrentHashMap<>(currentMetrics));
    }

    private void evaluateAlertRules(String metricName, BigDecimal value) {
        Flux.fromIterable(alertRuleMapper.selectList(
                        new LambdaQueryWrapper<AlertRule>()
                                .eq(AlertRule::getMetricName, metricName)
                                .eq(AlertRule::getEnabled, true)
                ))
                .filter(rule -> evaluateCondition(rule, value))
                .flatMap(rule -> triggerAlert(rule, value))
                .subscribe();
    }

    private boolean evaluateCondition(AlertRule rule, BigDecimal value) {
        return switch (rule.getOperator()) {
            case ">" -> value.compareTo(rule.getThreshold()) > 0;
            case ">=" -> value.compareTo(rule.getThreshold()) >= 0;
            case "<" -> value.compareTo(rule.getThreshold()) < 0;
            case "<=" -> value.compareTo(rule.getThreshold()) <= 0;
            case "==" -> value.compareTo(rule.getThreshold()) == 0;
            case "!=" -> value.compareTo(rule.getThreshold()) != 0;
            default -> false;
        };
    }

    private Mono<Void> triggerAlert(AlertRule rule, BigDecimal actualValue) {
        String message = String.format("告警触发: 规则[%s], 指标[%s], 当前值[%s], 阈值[%s], 操作符[%s], 严重程度[%s]",
                rule.getName(), rule.getMetricName(), actualValue, rule.getThreshold(),
                rule.getOperator(), rule.getSeverity());

        log.warn(message);

        return Flux.fromIterable(rule.getNotificationChannels())
                .flatMap(channel -> notificationService.createNotification(
                        channel,
                        "admin@example.com",
                        message
                ))
                .flatMap(notificationService::sendNotification)
                .then();
    }

    public Mono<AlertRule> createAlertRule(AlertRule rule) {
        return Mono.fromCallable(() -> {
            rule.setRuleId("rule_" + UUID.randomUUID().toString().substring(0, 8));
            alertRuleMapper.insert(rule);
            log.info("告警规则创建成功: ruleId={}", rule.getRuleId());
            return rule;
        });
    }

    public Flux<AlertRule> getAllAlertRules() {
        return Flux.fromIterable(alertRuleMapper.selectList(null));
    }
}
