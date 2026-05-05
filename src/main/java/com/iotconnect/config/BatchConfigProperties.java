package com.iotconnect.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "batch-config")
public class BatchConfigProperties {

    private DefaultConfig defaultConfig = new DefaultConfig();
    private Map<String, TypeConfig> types = new HashMap<>();

    public DefaultConfig getDefault() {
        return defaultConfig;
    }

    public void setDefault(DefaultConfig defaultConfig) {
        this.defaultConfig = defaultConfig;
    }

    public Map<String, TypeConfig> getTypes() {
        return types;
    }

    public void setTypes(Map<String, TypeConfig> types) {
        this.types = types;
    }

    public static class DefaultConfig {
        private int batchSize = 100;
        private int windowSeconds = 5;
        private int maxBufferSize = 10000;
        private boolean enabled = true;

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(int windowSeconds) {
            this.windowSeconds = windowSeconds;
        }

        public int getMaxBufferSize() {
            return maxBufferSize;
        }

        public void setMaxBufferSize(int maxBufferSize) {
            this.maxBufferSize = maxBufferSize;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class TypeConfig {
        private Integer batchSize;
        private Integer windowSeconds;
        private Integer maxBufferSize;
        private Boolean enabled;

        public Integer getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(Integer batchSize) {
            this.batchSize = batchSize;
        }

        public Integer getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(Integer windowSeconds) {
            this.windowSeconds = windowSeconds;
        }

        public Integer getMaxBufferSize() {
            return maxBufferSize;
        }

        public void setMaxBufferSize(Integer maxBufferSize) {
            this.maxBufferSize = maxBufferSize;
        }

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }
    }
}
