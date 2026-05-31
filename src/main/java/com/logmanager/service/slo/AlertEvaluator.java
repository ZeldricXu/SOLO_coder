package com.logmanager.service.slo;

import com.logmanager.domain.model.ErrorBudget;
import reactor.core.publisher.Mono;

@FunctionalInterface
public interface AlertEvaluator {
    Mono<Boolean> shouldAlert(ErrorBudget budget);
}
