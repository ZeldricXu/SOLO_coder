package com.logmanager.service.slo.budget;

import com.logmanager.domain.event.DomainEvent;
import com.logmanager.domain.event.EventPublisher;
import com.logmanager.domain.model.ErrorBudget;
import com.logmanager.domain.model.SLOConfig;
import com.logmanager.service.slo.ErrorBudgetManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class DefaultErrorBudgetManager implements ErrorBudgetManager {

    private final EventPublisher eventPublisher;
    private final Map<String, ErrorBudget> budgetStore = new ConcurrentHashMap<>();

    @Override
    public Mono<ErrorBudget> initialize(SLOConfig slo) {
        ErrorBudget budget = createErrorBudget(slo);
        budgetStore.put(slo.getSloId(), budget);
        return Mono.just(budget);
    }

    @Override
    public Mono<ErrorBudget> consume(String sloId, Double amount) {
        ErrorBudget budget = budgetStore.get(sloId);
        if (budget == null) {
            return Mono.error(new IllegalArgumentException("Error budget not found for SLO: " + sloId));
        }

        budget.setConsumedBudget(budget.getConsumedBudget() + amount);
        budget.setRemainingBudget(Math.max(0, budget.getTotalBudget() - budget.getConsumedBudget()));
        budget.setBurnRate(calculateBurnRate(budget));
        budget.setUpdatedAt(Instant.now());

        if (budget.getRemainingBudget() <= 0) {
            budget.setStatus("exhausted");
            eventPublisher.publish(new DomainEvent("error_budget.exhausted", sloId, "slo"));
        }

        return Mono.just(budget);
    }

    @Override
    public Mono<ErrorBudget> get(String sloId) {
        ErrorBudget budget = budgetStore.get(sloId);
        return budget != null ? Mono.just(budget) : Mono.empty();
    }

    @Override
    public Mono<ErrorBudget> refreshBurnRate(String sloId) {
        ErrorBudget budget = budgetStore.get(sloId);
        if (budget == null) {
            return Mono.empty();
        }
        budget.setBurnRate(calculateBurnRate(budget));
        budget.setUpdatedAt(Instant.now());
        return Mono.just(budget);
    }

    @Override
    public Mono<Boolean> isExhausted(String sloId) {
        ErrorBudget budget = budgetStore.get(sloId);
        return Mono.just(budget != null && budget.getRemainingBudget() <= 0);
    }

    @Override
    public void reset(String sloId) {
        budgetStore.remove(sloId);
    }

    private ErrorBudget createErrorBudget(SLOConfig slo) {
        ErrorBudget budget = new ErrorBudget();
        budget.setId(UUID.randomUUID().toString());
        budget.setBudgetId(UUID.randomUUID().toString());
        budget.setSloId(slo.getSloId());
        budget.setTotalBudget(100.0 - slo.getTargetPercentage());
        budget.setRemainingBudget(budget.getTotalBudget());
        budget.setConsumedBudget(0.0);
        budget.setBurnRate(0.0);
        budget.setWindowStart(Instant.now());
        budget.setWindowEnd(Instant.now().plus(slo.getWindow()));
        budget.setStatus("healthy");
        budget.setCreatedAt(Instant.now());
        budget.setUpdatedAt(Instant.now());
        return budget;
    }

    private double calculateBurnRate(ErrorBudget budget) {
        if (budget.getTotalBudget() <= 0) {
            return 0.0;
        }
        long windowSeconds = Duration.between(budget.getWindowStart(), budget.getWindowEnd()).getSeconds();
        long elapsedSeconds = Duration.between(budget.getWindowStart(), Instant.now()).getSeconds();
        if (elapsedSeconds <= 0) {
            return 0.0;
        }
        double consumedRate = budget.getConsumedBudget() / elapsedSeconds;
        double expectedRate = budget.getTotalBudget() / windowSeconds;
        return consumedRate / expectedRate;
    }
}
