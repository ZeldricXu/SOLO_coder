package com.streamsql.resilience;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "streamsql.resilience")
public class ResilienceConfig {

    private CircuitBreakerConfig circuitBreaker = new CircuitBreakerConfig();
    private RetryConfig retry = new RetryConfig();
    private FallbackConfig fallback = new FallbackConfig();

    @Data
    public static class CircuitBreakerConfig {
        private boolean enabled = true;
        private int failureThreshold = 5;
        private int successThreshold = 3;
        private long timeoutMs = 30000;
        private long halfOpenWaitMs = 60000;
        private int halfOpenMaxCalls = 1;
    }

    @Data
    public static class RetryConfig {
        private boolean enabled = true;
        private int maxAttempts = 3;
        private long initialDelayMs = 100;
        private long maxDelayMs = 10000;
        private double multiplier = 2.0;
        private boolean enableExponentialBackoff = true;
    }

    @Data
    public static class FallbackConfig {
        private boolean enabled = true;
        private boolean returnDefaultValue = true;
        private boolean cacheFallbackResult = false;
        private long cacheTtlMs = 60000;
    }

    public enum CircuitBreakerState {
        CLOSED,
        OPEN,
        HALF_OPEN
    }
}
