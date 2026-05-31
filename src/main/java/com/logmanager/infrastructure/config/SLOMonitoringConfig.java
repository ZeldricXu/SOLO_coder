package com.logmanager.infrastructure.config;

import com.logmanager.service.slo.AlertEvaluator;
import com.logmanager.service.slo.ErrorBudgetManager;
import com.logmanager.service.slo.MonitoredErrorBudgetManager;
import com.logmanager.service.slo.MonitoredSLICalculator;
import com.logmanager.service.slo.SLICalculator;
import com.logmanager.service.slo.SLOConfigRepository;
import com.logmanager.service.slo.alert.BurnRateAlertEvaluator;
import com.logmanager.service.slo.budget.DefaultErrorBudgetManager;
import com.logmanager.service.slo.calculator.DefaultSLICalculator;
import com.logmanager.service.slo.calculator.InMemorySLOConfigRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class SLOMonitoringConfig {

    @Value("${slo.monitoring.enabled:true}")
    private boolean monitoringEnabled;

    @Value("${slo.burn-rate-alert-threshold:1.0}")
    private double burnRateAlertThreshold;

    @Bean
    public SLOConfigRepository sloConfigRepository() {
        return new InMemorySLOConfigRepository();
    }

    @Bean
    public DefaultSLICalculator defaultSLICalculator() {
        return new DefaultSLICalculator();
    }

    @Bean
    @Primary
    public SLICalculator sliCalculator(DefaultSLICalculator defaultCalculator, MeterRegistry meterRegistry) {
        if (monitoringEnabled) {
            return new MonitoredSLICalculator(defaultCalculator, meterRegistry);
        }
        return defaultCalculator;
    }

    @Bean
    public DefaultErrorBudgetManager defaultErrorBudgetManager(com.logmanager.domain.event.EventPublisher eventPublisher) {
        return new DefaultErrorBudgetManager(eventPublisher);
    }

    @Bean
    @Primary
    public ErrorBudgetManager errorBudgetManager(DefaultErrorBudgetManager defaultManager, MeterRegistry meterRegistry) {
        if (monitoringEnabled) {
            return new MonitoredErrorBudgetManager(defaultManager, meterRegistry);
        }
        return defaultManager;
    }

    @Bean
    @Primary
    public AlertEvaluator alertEvaluator() {
        return new BurnRateAlertEvaluator(burnRateAlertThreshold);
    }
}
