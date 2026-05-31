package com.logmanager.slo;

import com.logmanager.domain.model.ErrorBudget;
import com.logmanager.service.slo.alert.BurnRateAlertEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import static org.junit.jupiter.api.Assertions.*;

class BurnRateAlertEvaluatorTest {

    private BurnRateAlertEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new BurnRateAlertEvaluator(1.0);
    }

    @Test
    void shouldAlertWhenBurnRateExceedsThreshold() {
        ErrorBudget budget = new ErrorBudget();
        budget.setBurnRate(1.5);

        Mono<Boolean> result = evaluator.shouldAlert(budget);

        StepVerifier.create(result)
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void shouldNotAlertWhenBurnRateBelowThreshold() {
        ErrorBudget budget = new ErrorBudget();
        budget.setBurnRate(0.8);

        Mono<Boolean> result = evaluator.shouldAlert(budget);

        StepVerifier.create(result)
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void shouldNotAlertWhenBurnRateEqualsThreshold() {
        ErrorBudget budget = new ErrorBudget();
        budget.setBurnRate(1.0);

        Mono<Boolean> result = evaluator.shouldAlert(budget);

        StepVerifier.create(result)
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void shouldNotAlertWhenBudgetIsNull() {
        Mono<Boolean> result = evaluator.shouldAlert(null);

        StepVerifier.create(result)
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void shouldNotAlertWhenBurnRateIsNull() {
        ErrorBudget budget = new ErrorBudget();
        budget.setBurnRate(null);

        Mono<Boolean> result = evaluator.shouldAlert(budget);

        StepVerifier.create(result)
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void shouldUseCustomThreshold() {
        BurnRateAlertEvaluator customEvaluator = new BurnRateAlertEvaluator(2.0);

        ErrorBudget budget = new ErrorBudget();
        budget.setBurnRate(1.5);

        StepVerifier.create(customEvaluator.shouldAlert(budget))
                .expectNext(false)
                .verifyComplete();

        budget.setBurnRate(2.5);
        StepVerifier.create(customEvaluator.shouldAlert(budget))
                .expectNext(true)
                .verifyComplete();
    }
}
