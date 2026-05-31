package com.streamsql.resilience;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class CircuitBreakerManager {

    private final ResilienceConfig config;
    private final Map<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();

    public CircuitBreaker getCircuitBreaker(String name) {
        return circuitBreakers.computeIfAbsent(name,
                k -> new CircuitBreaker(k, config.getCircuitBreaker()));
    }

    public <T> T execute(String circuitBreakerName, Supplier<T> operation, Supplier<T> fallback) {
        CircuitBreaker circuitBreaker = getCircuitBreaker(circuitBreakerName);

        if (!circuitBreaker.isCallPermitted()) {
            log.warn("Circuit breaker [{}] is OPEN, executing fallback", circuitBreakerName);
            return fallback.get();
        }

        try {
            T result = operation.get();
            circuitBreaker.recordSuccess();
            return result;
        } catch (Exception e) {
            log.error("Operation failed in circuit breaker [{}]", circuitBreakerName, e);
            circuitBreaker.recordFailure(e);
            return fallback.get();
        }
    }

    public void execute(String circuitBreakerName, Runnable operation, Runnable fallback) {
        CircuitBreaker circuitBreaker = getCircuitBreaker(circuitBreakerName);

        if (!circuitBreaker.isCallPermitted()) {
            log.warn("Circuit breaker [{}] is OPEN, executing fallback", circuitBreakerName);
            fallback.run();
            return;
        }

        try {
            operation.run();
            circuitBreaker.recordSuccess();
        } catch (Exception e) {
            log.error("Operation failed in circuit breaker [{}]", circuitBreakerName, e);
            circuitBreaker.recordFailure(e);
            fallback.run();
        }
    }

    public <T> T executeWithRetry(String circuitBreakerName, Supplier<T> operation, Supplier<T> fallback) {
        CircuitBreaker circuitBreaker = getCircuitBreaker(circuitBreakerName);
        ResilienceConfig.RetryConfig retryConfig = config.getRetry();

        AtomicInteger attempt = new AtomicInteger(0);
        long delay = retryConfig.getInitialDelayMs();

        while (attempt.get() < retryConfig.getMaxAttempts()) {
            if (!circuitBreaker.isCallPermitted()) {
                log.warn("Circuit breaker [{}] is OPEN, executing fallback", circuitBreakerName);
                return fallback.get();
            }

            try {
                T result = operation.get();
                circuitBreaker.recordSuccess();
                return result;
            } catch (Exception e) {
                attempt.incrementAndGet();
                log.warn("Attempt {}/{} failed for circuit breaker [{}]",
                        attempt.get(), retryConfig.getMaxAttempts(), circuitBreakerName, e);

                if (attempt.get() >= retryConfig.getMaxAttempts()) {
                    circuitBreaker.recordFailure(e);
                    return fallback.get();
                }

                try {
                    Thread.sleep(delay);
                    if (retryConfig.isEnableExponentialBackoff()) {
                        delay = Math.min((long) (delay * retryConfig.getMultiplier()), retryConfig.getMaxDelayMs());
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return fallback.get();
                }
            }
        }

        return fallback.get();
    }

    public void resetCircuitBreaker(String name) {
        CircuitBreaker circuitBreaker = circuitBreakers.get(name);
        if (circuitBreaker != null) {
            circuitBreaker.reset();
        }
    }

    public void resetAll() {
        circuitBreakers.values().forEach(CircuitBreaker::reset);
    }

    public Map<String, CircuitBreaker.Metrics> getAllMetrics() {
        Map<String, CircuitBreaker.Metrics> metrics = new ConcurrentHashMap<>();
        circuitBreakers.forEach((name, cb) -> metrics.put(name, cb.getMetrics()));
        return metrics;
    }
}
