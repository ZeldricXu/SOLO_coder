package com.meeting.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "meeting.lock")
public class LockConfig {

    private boolean enabled = true;
    private long defaultTimeoutSeconds = 30;
    private Map<String, LockTypeConfig> typeConfigs = new HashMap<>();

    @Data
    public static class LockTypeConfig {
        private long lockTimeoutSeconds;
        private int priority = 5;
        private String description;
    }

    public LockTypeConfig getConfigForType(String type) {
        if (type == null || type.isEmpty()) {
            return getDefaultConfig();
        }
        return typeConfigs.getOrDefault(type, getDefaultConfig());
    }

    public LockTypeConfig getDefaultConfig() {
        LockTypeConfig defaultConfig = new LockTypeConfig();
        defaultConfig.setLockTimeoutSeconds(defaultTimeoutSeconds);
        defaultConfig.setPriority(5);
        defaultConfig.setDescription("默认锁定配置");
        return defaultConfig;
    }

    public long getTimeoutForType(String type) {
        return getConfigForType(type).getLockTimeoutSeconds();
    }

    public int getPriorityForType(String type) {
        return getConfigForType(type).getPriority();
    }

    public boolean hasConfigForType(String type) {
        return type != null && typeConfigs.containsKey(type);
    }

    public void addOrUpdateConfig(String type, LockTypeConfig config) {
        typeConfigs.put(type, config);
    }

    public void removeConfig(String type) {
        typeConfigs.remove(type);
    }
}
