package com.streamsql.resilience;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class CircuitBreaker {

    @Getter
    private final String name;
    private final ResilienceConfig.CircuitBreakerConfig config;

    private final AtomicReference<ResilienceConfig.CircuitBreakerState> state = new AtomicReference<>(ResilienceConfig.CircuitBreakerState.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private volatile LocalDateTime lastFailureTime;
    private volatile LocalDateTime openTime;

    public CircuitBreaker(String name, ResilienceConfig.CircuitBreakerConfig config) {
        this.name = name;
        this.config = config;
    }

    public boolean isCallPermitted() {
        ResilienceConfig.CircuitBreakerState currentState = state.get();

        return switch (currentState) {
            case CLOSED -> true;
            case HALF_OPEN -> true;
            case OPEN -> {
                if (LocalDateTime.now().isAfter(openTime.plusNanos(config.getHalfOpenWaitMs() * 1_000_000))) {
                    state.compareAndSet(ResilienceConfig.CircuitBreakerState.OPEN, ResilienceConfig.CircuitBreakerState.HALF_OPEN);
                    successCount.set(0);
                    log.info("Circuit breaker [{}] transitioning from OPEN to HALF_OPEN", name);
                    yield true;
                }
                yield false;
            }
        };
    }

    public void recordSuccess() {
        successCount.incrementAndGet();
        failureCount.set(0);
        lastFailureTime = null;

        if (state.get() == ResilienceConfig.CircuitBreakerState.HALF_OPEN) {
            if (successCount.get() >= config.getSuccessThreshold()) {
                state.set(ResilienceConfig.CircuitBreakerState.CLOSED);
                log.info("Circuit breaker [{}] transitioning from HALF_OPEN to CLOSED", name);
            }
        }
    }

    public void recordFailure(Exception e) {
        failureCount.incrementAndGet();
        lastFailureTime = LocalDateTime.now();
        successCount.set(0);

        ResilienceConfig.CircuitBreakerState currentState = state.get();

        if (currentState == ResilienceConfig.CircuitBreakerState.CLOSED) {
            if (failureCount.get() >= config.getFailureThreshold()) {
                state.set(ResilienceConfig.CircuitBreakerState.OPEN);
                openTime = LocalDateTime.now();
                log.warn("Circuit breaker [{}] OPENED after {} failures", name, failureCount.get());
            }
        } else if (currentState == ResilienceConfig.CircuitBreakerState.HALF_OPEN) {
            state.set(ResilienceConfig.CircuitBreakerState.OPEN);
            openTime = LocalDateTime.now();
            log.warn("Circuit breaker [{}] transitioned from HALF_OPEN to OPEN after failure", name);
        }
    }

    public ResilienceConfig.CircuitBreakerState getState() {
        return state.get();
    }

    public int getFailureCount() {
        return failureCount.get();
    }

    public int getSuccessCount() {
        return successCount.get();
    }

    public void reset() {
        state.set(ResilienceConfig.CircuitBreakerState.CLOSED);
        failureCount.set(0);
        successCount.set(0);
        lastFailureTime = null;
        openTime = null;
        log.info("Circuit breaker [{}] reset", name);
    }

    public record Metrics(
            String name,
            ResilienceConfig.CircuitBreakerState state,
            int failureCount,
            int successCount,
            LocalDateTime lastFailureTime,
            LocalDateTime openTime
    ) {}

    public Metrics getMetrics() {
        return new Metrics(name, getState(), getFailureCount(), getSuccessCount(), lastFailureTime, openTime);
    }
}
