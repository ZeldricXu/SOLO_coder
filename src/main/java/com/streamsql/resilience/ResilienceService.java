package com.streamsql.resilience;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResilienceService {

    private final CircuitBreakerManager circuitBreakerManager;
    private final ResilienceConfig config;

    public <T> T executeWithCircuitBreaker(String operationName, Supplier<T> operation, Supplier<T> fallback) {
        return circuitBreakerManager.execute(operationName, operation, fallback);
    }

    public void executeWithCircuitBreaker(String operationName, Runnable operation, Runnable fallback) {
        circuitBreakerManager.execute(operationName, operation, fallback);
    }

    public <T> T executeWithRetry(String operationName, Supplier<T> operation, Supplier<T> fallback) {
        return circuitBreakerManager.executeWithRetry(operationName, operation, fallback);
    }

    public <T> T executeWithFullProtection(String operationName, Supplier<T> operation, Supplier<T> fallback) {
        log.debug("Executing operation [{}] with full resilience protection", operationName);

        ResilienceConfig.RetryConfig retryConfig = config.getRetry();
        int maxAttempts = retryConfig.getMaxAttempts();
        long delay = retryConfig.getInitialDelayMs();

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                return circuitBreakerManager.execute(operationName, operation, fallback);
            } catch (Exception e) {
                if (attempt == maxAttempts - 1) {
                    log.error("All attempts failed for operation [{}]", operationName, e);
                    return fallback.get();
                }

                log.warn("Attempt {}/{} failed for operation [{}], retrying in {}ms",
                        attempt + 1, maxAttempts, operationName, delay, e);

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

    public void resetCircuitBreaker(String operationName) {
        circuitBreakerManager.resetCircuitBreaker(operationName);
    }

    public void resetAllCircuitBreakers() {
        circuitBreakerManager.resetAll();
    }

    public Map<String, CircuitBreaker.Metrics> getCircuitBreakerMetrics() {
        return circuitBreakerManager.getAllMetrics();
    }

    public ResilienceConfig getConfig() {
        return config;
    }
}
