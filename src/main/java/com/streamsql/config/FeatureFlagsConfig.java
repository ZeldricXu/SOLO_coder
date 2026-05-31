package com.streamsql.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "streamsql.features")
public class FeatureFlagsConfig {

    private FeatureFlag streamingProcessing = new FeatureFlag(false, "流式处理模式");
    private FeatureFlag eventDrivenArchitecture = new FeatureFlag(false, "事件驱动架构");
    private FeatureFlag circuitBreaker = new FeatureFlag(false, "熔断器降级策略");
    private FeatureFlag retryPolicy = new FeatureFlag(false, "重试策略");
    private FeatureFlag fallbackPolicy = new FeatureFlag(false, "降级策略");

    @Data
    public static class FeatureFlag {
        private boolean enabled;
        private String description;

        public FeatureFlag() {
        }

        public FeatureFlag(boolean enabled, String description) {
            this.enabled = enabled;
            this.description = description;
        }
    }

    public boolean isStreamingProcessingEnabled() {
        return streamingProcessing.isEnabled();
    }

    public boolean isEventDrivenArchitectureEnabled() {
        return eventDrivenArchitecture.isEnabled();
    }

    public boolean isCircuitBreakerEnabled() {
        return circuitBreaker.isEnabled();
    }

    public boolean isRetryPolicyEnabled() {
        return retryPolicy.isEnabled();
    }

    public boolean isFallbackPolicyEnabled() {
        return fallbackPolicy.isEnabled();
    }
}
