package com.logmanager.service;

import com.logmanager.domain.model.ErrorBudget;
import com.logmanager.domain.model.SLOConfig;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.Map;

public interface SLOService {
    Mono<SLOConfig> createSLO(String name, String serviceName, Double targetPercentage, Duration window, Map<String, String> sliConfig);
    Mono<SLOConfig> updateSLO(String sloId, Map<String, Object> updates);
    Mono<SLOConfig> getSLO(String sloId);
    Flux<SLOConfig> getSLOsByService(String serviceName);
    Mono<Void> deleteSLO(String sloId);
    Mono<Double> calculateSLI(String sloId);
    Mono<ErrorBudget> getErrorBudget(String sloId);
    Mono<ErrorBudget> consumeErrorBudget(String sloId, Double amount);
    Mono<Boolean> checkBurnRateAlert(String sloId);
    Flux<ErrorBudget> getErrorBudgetHistory(String sloId);

    Mono<Map<String, Object>> getMonitoringStats();
}
