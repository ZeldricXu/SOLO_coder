package com.eventticket.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "verification.retry")
public class VerificationRetryConfig {

    private Map<String, RetryConfig> strategies = new HashMap<>();
    private String defaultStrategy = "medium";
    private int capacitySmallThreshold = 1000;
    private int capacityLargeThreshold = 10000;

    @Data
    public static class RetryConfig {
        private int maxRetries;
        private int retryDelaySeconds;
        private int backoffMultiplier;
        private String description;
    }

    public RetryConfig getRetryConfig(String strategy) {
        RetryConfig config = strategies.get(strategy);
        if (config == null) {
            config = strategies.get(defaultStrategy);
        }
        return config;
    }

    public RetryConfig getRetryConfigByCapacity(int eventCapacity) {
        String strategy;
        if (eventCapacity <= capacitySmallThreshold) {
            strategy = "small";
        } else if (eventCapacity >= capacityLargeThreshold) {
            strategy = "large";
        } else {
            strategy = "medium";
        }
        return getRetryConfig(strategy);
    }

    public int getMaxRetries(int eventCapacity) {
        RetryConfig config = getRetryConfigByCapacity(eventCapacity);
        return config != null ? config.getMaxRetries() : 3;
    }

    public int getRetryDelaySeconds(int eventCapacity) {
        RetryConfig config = getRetryConfigByCapacity(eventCapacity);
        return config != null ? config.getRetryDelaySeconds() : 1;
    }

    public int getBackoffMultiplier(int eventCapacity) {
        RetryConfig config = getRetryConfigByCapacity(eventCapacity);
        return config != null ? config.getBackoffMultiplier() : 2;
    }
}
