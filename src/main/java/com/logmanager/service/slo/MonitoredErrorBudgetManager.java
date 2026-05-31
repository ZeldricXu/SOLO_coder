package com.logmanager.service.slo;

import com.logmanager.domain.model.ErrorBudget;
import com.logmanager.domain.model.SLOConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@RequiredArgsConstructor
public class MonitoredErrorBudgetManager implements ErrorBudgetManager {

    private final ErrorBudgetManager delegate;
    private final MeterRegistry meterRegistry;

    private final Timer consumeTimer;
    private final Timer initializeTimer;
    private final Counter consumeCounter;
    private final Counter exhaustedCounter;
    private final DistributionSummary burnRateDistribution;
    private final DistributionSummary consumedDistribution;
    private final AtomicLong totalConsumed = new AtomicLong(0);
    private final AtomicLong totalInitialized = new AtomicLong(0);

    public MonitoredErrorBudgetManager(ErrorBudgetManager delegate, MeterRegistry meterRegistry) {
        this.delegate = delegate;
        this.meterRegistry = meterRegistry;

        this.consumeTimer = Timer.builder("slo.budget.consume.duration")
                .description("Time taken to consume error budget")
                .register(meterRegistry);
        this.initializeTimer = Timer.builder("slo.budget.initialize.duration")
                .description("Time taken to initialize error budget")
                .register(meterRegistry);
        this.consumeCounter = Counter.builder("slo.budget.consume.count")
                .description("Number of error budget consumptions")
                .register(meterRegistry);
        this.exhaustedCounter = Counter.builder("slo.budget.exhausted.count")
                .description("Number of times error budget was exhausted")
                .register(meterRegistry);
        this.burnRateDistribution = DistributionSummary.builder("slo.budget.burn.rate")
                .description("Distribution of burn rates")
                .register(meterRegistry);
        this.consumedDistribution = DistributionSummary.builder("slo.budget.consumed.amount")
                .description("Distribution of consumed budget amounts")
                .register(meterRegistry);

        meterRegistry.gauge("slo.budget.total.consumed", totalConsumed, AtomicLong::get);
        meterRegistry.gauge("slo.budget.total.initialized", totalInitialized, AtomicLong::get);
    }

    @Override
    public Mono<ErrorBudget> initialize(SLOConfig slo) {
        long start = System.nanoTime();
        return delegate.initialize(slo)
                .doOnNext(budget -> {
                    initializeTimer.record(Duration.ofNanos(System.nanoTime() - start));
                    totalInitialized.incrementAndGet();
                    meterRegistry.gauge("slo.budget.remaining", slo.getName(), budget.getRemainingBudget());
                    meterRegistry.gauge("slo.budget.total", slo.getName(), budget.getTotalBudget());
                    log.debug("Error budget initialized for '{}': total={}", slo.getName(), budget.getTotalBudget());
                });
    }

    @Override
    public Mono<ErrorBudget> consume(String sloId, Double amount) {
        long start = System.nanoTime();
        return delegate.consume(sloId, amount)
                .doOnNext(budget -> {
                    consumeTimer.record(Duration.ofNanos(System.nanoTime() - start));
                    consumeCounter.increment();
                    consumedDistribution.record(amount);
                    totalConsumed.addAndGet(amount.longValue());
                    burnRateDistribution.record(budget.getBurnRate());

                    if (budget.getRemainingBudget() <= 0) {
                        exhaustedCounter.increment();
                        log.warn("Error budget exhausted for sloId: {}", sloId);
                    }

                    log.debug("Error budget consumed for sloId: {}, amount: {}, remaining: {}",
                            sloId, amount, budget.getRemainingBudget());
                });
    }

    @Override
    public Mono<ErrorBudget> get(String sloId) {
        return delegate.get(sloId);
    }

    @Override
    public Mono<ErrorBudget> refreshBurnRate(String sloId) {
        return delegate.refreshBurnRate(sloId)
                .doOnNext(budget -> burnRateDistribution.record(budget.getBurnRate()));
    }

    @Override
    public Mono<Boolean> isExhausted(String sloId) {
        return delegate.isExhausted(sloId);
    }

    @Override
    public void reset(String sloId) {
        delegate.reset(sloId);
        log.debug("Error budget reset for sloId: {}", sloId);
    }

    public long getTotalConsumed() {
        return totalConsumed.get();
    }

    public long getTotalInitialized() {
        return totalInitialized.get();
    }
}
