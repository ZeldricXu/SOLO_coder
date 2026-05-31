package com.streamsql.feature;

import com.streamsql.config.FeatureFlagsConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureFlagService {

    private final FeatureFlagsConfig featureFlagsConfig;

    public boolean isEnabled(String featureName) {
        return switch (featureName.toLowerCase()) {
            case "streaming-processing" -> featureFlagsConfig.isStreamingProcessingEnabled();
            case "event-driven-architecture" -> featureFlagsConfig.isEventDrivenArchitectureEnabled();
            case "circuit-breaker" -> featureFlagsConfig.isCircuitBreakerEnabled();
            case "retry-policy" -> featureFlagsConfig.isRetryPolicyEnabled();
            case "fallback-policy" -> featureFlagsConfig.isFallbackPolicyEnabled();
            default -> {
                log.warn("Unknown feature: {}", featureName);
                yield false;
            }
        };
    }

    public <T> T executeWithFeature(String featureName, Supplier<T> featureLogic, Supplier<T> fallbackLogic) {
        if (isEnabled(featureName)) {
            log.debug("Feature [{}] is enabled, executing feature logic", featureName);
            try {
                return featureLogic.get();
            } catch (Exception e) {
                log.error("Feature [{}] execution failed, falling back", featureName, e);
                return fallbackLogic.get();
            }
        } else {
            log.debug("Feature [{}] is disabled, executing fallback logic", featureName);
            return fallbackLogic.get();
        }
    }

    public void executeWithFeature(String featureName, Runnable featureLogic, Runnable fallbackLogic) {
        if (isEnabled(featureName)) {
            log.debug("Feature [{}] is enabled, executing feature logic", featureName);
            try {
                featureLogic.run();
            } catch (Exception e) {
                log.error("Feature [{}] execution failed, falling back", featureName, e);
                fallbackLogic.run();
            }
        } else {
            log.debug("Feature [{}] is disabled, executing fallback logic", featureName);
            fallbackLogic.run();
        }
    }

    public Map<String, Object> getAllFeatureStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("streamingProcessing", featureFlagsConfig.isStreamingProcessingEnabled());
        status.put("eventDrivenArchitecture", featureFlagsConfig.isEventDrivenArchitectureEnabled());
        status.put("circuitBreaker", featureFlagsConfig.isCircuitBreakerEnabled());
        status.put("retryPolicy", featureFlagsConfig.isRetryPolicyEnabled());
        status.put("fallbackPolicy", featureFlagsConfig.isFallbackPolicyEnabled());
        return status;
    }

    public Map<String, String> getAllFeatureDescriptions() {
        Map<String, String> descriptions = new HashMap<>();
        descriptions.put("streamingProcessing", featureFlagsConfig.getStreamingProcessing().getDescription());
        descriptions.put("eventDrivenArchitecture", featureFlagsConfig.getEventDrivenArchitecture().getDescription());
        descriptions.put("circuitBreaker", featureFlagsConfig.getCircuitBreaker().getDescription());
        descriptions.put("retryPolicy", featureFlagsConfig.getRetryPolicy().getDescription());
        descriptions.put("fallbackPolicy", featureFlagsConfig.getFallbackPolicy().getDescription());
        return descriptions;
    }
}
