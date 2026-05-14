package com.assetinventory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableConfigurationProperties
@ConfigurationProperties(prefix = "inventory.lock")
public class LockConfig {

    private boolean enabled = true;
    private Map<String, LockPriorityConfig> priority = new HashMap<>();
    private String defaultPriority = "normal";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, LockPriorityConfig> getPriority() {
        return priority;
    }

    public void setPriority(Map<String, LockPriorityConfig> priority) {
        this.priority = priority;
    }

    public String getDefaultPriority() {
        return defaultPriority;
    }

    public void setDefaultPriority(String defaultPriority) {
        this.defaultPriority = defaultPriority;
    }

    public LockPriorityConfig getPriorityConfig(String priorityName) {
        return priority.getOrDefault(priorityName.toLowerCase(), priority.get(defaultPriority.toLowerCase()));
    }

    public long getTimeoutMs(String priorityName) {
        LockPriorityConfig config = getPriorityConfig(priorityName);
        return TimeUnit.SECONDS.toMillis(config.getTimeoutSeconds());
    }

    public int getLevel(String priorityName) {
        LockPriorityConfig config = getPriorityConfig(priorityName);
        return config.getLevel();
    }

    public String getName(String priorityName) {
        LockPriorityConfig config = getPriorityConfig(priorityName);
        return config.getName();
    }

    public String getDescription(String priorityName) {
        LockPriorityConfig config = getPriorityConfig(priorityName);
        return config.getDescription();
    }

    public static class LockPriorityConfig {
        private String name;
        private int level;
        private int timeoutSeconds;
        private String description;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getLevel() {
            return level;
        }

        public void setLevel(int level) {
            this.level = level;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }
}
