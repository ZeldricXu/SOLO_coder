package com.logmanager.service.slo.alert;

import com.logmanager.domain.model.ErrorBudget;
import com.logmanager.service.slo.AlertEvaluator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class BurnRateAlertEvaluator implements AlertEvaluator {

    private static final double DEFAULT_THRESHOLD = 1.0;
    private final double threshold;

    public BurnRateAlertEvaluator() {
        this(DEFAULT_THRESHOLD);
    }

    public BurnRateAlertEvaluator(double threshold) {
        this.threshold = threshold;
    }

    @Override
    public Mono<Boolean> shouldAlert(ErrorBudget budget) {
        if (budget == null || budget.getBurnRate() == null) {
            return Mono.just(false);
        }
        return Mono.just(budget.getBurnRate() > threshold);
    }
}
