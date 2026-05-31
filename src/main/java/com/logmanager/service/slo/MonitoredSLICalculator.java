package com.logmanager.service.slo;

import com.logmanager.domain.model.SLOConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@RequiredArgsConstructor
public class MonitoredSLICalculator implements SLICalculator {

    private final SLICalculator delegate;
    private final MeterRegistry meterRegistry;
    private final Timer calculateTimer;
    private final AtomicLong calculationCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);

    public MonitoredSLICalculator(SLICalculator delegate, MeterRegistry meterRegistry) {
        this.delegate = delegate;
        this.meterRegistry = meterRegistry;
        this.calculateTimer = Timer.builder("slo.sli.calculate.duration")
                .description("Time taken to calculate SLI")
                .register(meterRegistry);

        meterRegistry.gauge("slo.sli.calculation.count", calculationCount, AtomicLong::get);
        meterRegistry.gauge("slo.sli.calculation.error.count", errorCount, AtomicLong::get);
    }

    @Override
    public Mono<Double> calculate(SLOConfig slo) {
        long start = System.nanoTime();
        return delegate.calculate(slo)
                .doOnNext(value -> {
                    long duration = System.nanoTime() - start;
                    calculateTimer.record(Duration.ofNanos(duration));
                    calculationCount.incrementAndGet();
                    meterRegistry.gauge("slo.sli.value", slo.getName(), String.valueOf(value));
                    log.debug("SLI calculated for '{}': {}, duration: {}ns", slo.getName(), value, duration);
                })
                .doOnError(error -> {
                    errorCount.incrementAndGet();
                    log.error("SLI calculation failed for '{}': {}", slo.getName(), error.getMessage());
                });
    }

    @Override
    public String getType() {
        return "monitored-" + delegate.getType();
    }

    public long getCalculationCount() {
        return calculationCount.get();
    }

    public long getErrorCount() {
        return errorCount.get();
    }
}
