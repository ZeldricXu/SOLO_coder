package com.logmanager.service.impl;

import com.logmanager.domain.event.DomainEvent;
import com.logmanager.domain.event.EventPublisher;
import com.logmanager.domain.model.ErrorBudget;
import com.logmanager.domain.model.SLOConfig;
import com.logmanager.service.SLOService;
import com.logmanager.service.slo.AlertEvaluator;
import com.logmanager.service.slo.ErrorBudgetManager;
import com.logmanager.service.slo.MonitoredErrorBudgetManager;
import com.logmanager.service.slo.MonitoredSLICalculator;
import com.logmanager.service.slo.SLICalculator;
import com.logmanager.service.slo.SLOConfigRepository;
import com.logmanager.service.slo.budget.DefaultErrorBudgetManager;
import com.logmanager.service.slo.calculator.DefaultSLICalculator;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SLOServiceImpl implements SLOService {

    private final SLOConfigRepository sloConfigRepository;
    private final SLICalculator sliCalculator;
    private final ErrorBudgetManager errorBudgetManager;
    private final AlertEvaluator alertEvaluator;
    private final EventPublisher eventPublisher;
    private final DefaultSLICalculator defaultSLICalculator;
    private final DefaultErrorBudgetManager defaultErrorBudgetManager;

    @PostConstruct
    public void init() {
        log.info("SLOService initialized with repository, calculator, budget manager, and alert evaluator");
    }

    @Override
    public Mono<SLOConfig> createSLO(String name, String serviceName, Double targetPercentage, Duration window, Map<String, String> sliConfig) {
        SLOConfig slo = createSLOConfig(name, serviceName, targetPercentage, window, sliConfig);

        return sloConfigRepository.save(slo)
                .flatMap(saved -> errorBudgetManager.initialize(saved)
                        .doOnSuccess(budget -> eventPublisher.publish(
                                new DomainEvent("slo.created", saved.getSloId(), "slo")))
                        .then(Mono.just(saved)));
    }

    @Override
    public Mono<SLOConfig> updateSLO(String sloId, Map<String, Object> updates) {
        return sloConfigRepository.findById(sloId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("SLO not found: " + sloId)))
                .flatMap(slo -> {
                    applyUpdates(slo, updates);
                    slo.setUpdatedAt(Instant.now());
                    return sloConfigRepository.save(slo)
                            .doOnSuccess(v -> eventPublisher.publish(
                                    new DomainEvent("slo.updated", sloId, "slo")));
                });
    }

    @Override
    public Mono<SLOConfig> getSLO(String sloId) {
        return sloConfigRepository.findById(sloId);
    }

    @Override
    public Flux<SLOConfig> getSLOsByService(String serviceName) {
        return sloConfigRepository.findByServiceName(serviceName);
    }

    @Override
    public Mono<Void> deleteSLO(String sloId) {
        return sloConfigRepository.existsById(sloId)
                .flatMap(exists -> {
                    if (!exists) {
                        return Mono.empty();
                    }
                    return sloConfigRepository.deleteById(sloId)
                            .doOnSuccess(v -> {
                                errorBudgetManager.reset(sloId);
                                eventPublisher.publish(new DomainEvent("slo.deleted", sloId, "slo"));
                            });
                });
    }

    @Override
    public Mono<Double> calculateSLI(String sloId) {
        return sloConfigRepository.findById(sloId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("SLO not found: " + sloId)))
                .flatMap(sliCalculator::calculate);
    }

    @Override
    public Mono<ErrorBudget> getErrorBudget(String sloId) {
        return errorBudgetManager.get(sloId);
    }

    @Override
    public Mono<ErrorBudget> consumeErrorBudget(String sloId, Double amount) {
        return errorBudgetManager.consume(sloId, amount);
    }

    @Override
    public Mono<Boolean> checkBurnRateAlert(String sloId) {
        return errorBudgetManager.get(sloId)
                .switchIfEmpty(Mono.just(false))
                .flatMap(alertEvaluator::shouldAlert);
    }

    @Override
    public Flux<ErrorBudget> getErrorBudgetHistory(String sloId) {
        return errorBudgetManager.get(sloId)
                .flux();
    }

    @Override
    public Mono<Map<String, Object>> getMonitoringStats() {
        Map<String, Object> stats = new HashMap<>();

        if (sliCalculator instanceof MonitoredSLICalculator) {
            MonitoredSLICalculator monitored = (MonitoredSLICalculator) sliCalculator;
            stats.put("sliCalculationCount", monitored.getCalculationCount());
            stats.put("sliCalculationErrorCount", monitored.getErrorCount());
        } else {
            stats.put("sliCalculationCount", 0);
            stats.put("sliCalculationErrorCount", 0);
        }

        if (errorBudgetManager instanceof MonitoredErrorBudgetManager) {
            MonitoredErrorBudgetManager monitored = (MonitoredErrorBudgetManager) errorBudgetManager;
            stats.put("budgetTotalConsumed", monitored.getTotalConsumed());
            stats.put("budgetTotalInitialized", monitored.getTotalInitialized());
        } else {
            stats.put("budgetTotalConsumed", 0);
            stats.put("budgetTotalInitialized", 0);
        }

        stats.put("monitoringEnabled",
                sliCalculator instanceof MonitoredSLICalculator
                        || errorBudgetManager instanceof MonitoredErrorBudgetManager);

        return sloConfigRepository.findAll().count()
                .doOnNext(count -> stats.put("totalSLOCount", count))
                .thenReturn(stats);
    }

    private SLOConfig createSLOConfig(String name, String serviceName, Double targetPercentage, Duration window, Map<String, String> sliConfig) {
        SLOConfig slo = new SLOConfig();
        slo.setId(UUID.randomUUID().toString());
        slo.setSloId(UUID.randomUUID().toString());
        slo.setName(name);
        slo.setServiceName(serviceName);
        slo.setTargetPercentage(targetPercentage);
        slo.setWindow(window);
        slo.setSliConfig(sliConfig);
        slo.setEnabled(true);
        slo.setCreatedAt(Instant.now());
        slo.setUpdatedAt(Instant.now());
        return slo;
    }

    private void applyUpdates(SLOConfig slo, Map<String, Object> updates) {
        if (updates.containsKey("name")) {
            slo.setName((String) updates.get("name"));
        }
        if (updates.containsKey("targetPercentage")) {
            slo.setTargetPercentage((Double) updates.get("targetPercentage"));
        }
        if (updates.containsKey("enabled")) {
            slo.setEnabled((Boolean) updates.get("enabled"));
        }
    }
}
