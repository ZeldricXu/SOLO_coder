package com.assetmanage.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "asset-lock")
public class LockConfigProperties {

    private boolean enabled = true;
    private LockConfig defaultConfig = new LockConfig();
    private Map<String, LockConfig> typeConfigs = new HashMap<>();
    private HighValueLockConfig highValueConfig = new HighValueLockConfig();

    public LockConfig getConfigForAsset(String assetType, java.math.BigDecimal assetValue) {
        if (assetValue != null && highValueConfig.getThreshold() != null
                && assetValue.compareTo(highValueConfig.getThreshold()) >= 0) {
            return highValueConfig;
        }

        if (typeConfigs.containsKey(assetType)) {
            return typeConfigs.get(assetType);
        }

        return defaultConfig;
    }

    @Data
    public static class LockConfig {
        private int timeoutSeconds = 30;
        private int maxRetryCount = 3;
        private int retryDelayMs = 1000;
        private LockGranularity granularity = LockGranularity.ASSET;
        private java.math.BigDecimal minValueThreshold = java.math.BigDecimal.ZERO;
    }

    @Data
    public static class HighValueLockConfig extends LockConfig {
        private java.math.BigDecimal threshold = new java.math.BigDecimal("50000");
    }

    public enum LockGranularity {
        ASSET,
        CATEGORY
    }
}
