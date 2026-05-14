package com.assetmanage.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "depreciation")
public class DepreciationConfigProperties {

    private boolean enabled = true;
    private PreCalculateConfig preCalculate = new PreCalculateConfig();
    private Map<String, DepreciationMethodConfig> methods = new HashMap<>();

    public boolean isMethodEnabled(String methodCode) {
        DepreciationMethodConfig config = methods.get(methodCode);
        return config != null && config.isEnabled();
    }

    public DepreciationMethodConfig getMethodConfig(String methodCode) {
        return methods.get(methodCode);
    }

    @Data
    public static class PreCalculateConfig {
        private boolean enabled = true;
        private int preCalculateDays = 3;
        private int cacheExpireMinutes = 60;
    }

    @Data
    public static class DepreciationMethodConfig {
        private String name;
        private boolean enabled = true;
        private String description;
        private String formula;
    }
}
