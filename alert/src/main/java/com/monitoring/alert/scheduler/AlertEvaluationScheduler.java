package com.monitoring.alert.scheduler;

import com.monitoring.alert.service.AlertEvaluationService;
import com.monitoring.config.service.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class AlertEvaluationScheduler {

    private final AlertEvaluationService alertEvaluationService;
    private final ConfigService configService;

    private final Map<String, Double> currentMetrics = new ConcurrentHashMap<>();

    public void updateMetric(String metricName, Double value) {
        currentMetrics.put(metricName, value);
    }

    public void updateMetrics(Map<String, Double> metrics) {
        currentMetrics.putAll(metrics);
    }

    @Scheduled(fixedDelayString = "${alert.evaluation.interval:15000}")
    public void evaluateAlerts() {
        long evalInterval = configService.getParameterOrDefault("alert_config", "evaluationInterval", 15000L);
        log.debug("Starting alert evaluation, metrics count={}", currentMetrics.size());

        alertEvaluationService.evaluateAll(currentMetrics)
                .onErrorResume(e -> {
                    log.error("Alert evaluation failed", e);
                    return Mono.empty();
                })
                .subscribe();
    }
}
