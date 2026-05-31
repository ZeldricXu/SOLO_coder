package com.logmanager.service.slo;

import com.logmanager.domain.model.ErrorBudget;
import com.logmanager.domain.model.SLOConfig;
import reactor.core.publisher.Mono;

public interface ErrorBudgetManager {
    Mono<ErrorBudget> initialize(SLOConfig slo);

    Mono<ErrorBudget> consume(String sloId, Double amount);

    Mono<ErrorBudget> get(String sloId);

    Mono<ErrorBudget> refreshBurnRate(String sloId);

    Mono<Boolean> isExhausted(String sloId);

    void reset(String sloId);
}
