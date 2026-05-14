package com.hotelbooking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "hotelbooking.lock")
public class LockTimeoutConfig {

    private Map<String, LockTimeoutConfigEntry> timeouts = new HashMap<>();

    private String defaultLevel = "NORMAL";

    public Map<String, LockTimeoutConfigEntry> getTimeouts() {
        return timeouts;
    }

    public void setTimeouts(Map<String, LockTimeoutConfigEntry> timeouts) {
        this.timeouts = timeouts;
    }

    public String getDefaultLevel() {
        return defaultLevel;
    }

    public void setDefaultLevel(String defaultLevel) {
        this.defaultLevel = defaultLevel;
    }

    public long getTimeoutMillis(String customerLevel) {
        LockTimeoutConfigEntry config = timeouts.get(customerLevel.toUpperCase());
        if (config == null) {
            config = timeouts.get(defaultLevel);
        }
        if (config == null) {
            return 10000;
        }
        return config.getTimeoutMillis();
    }

    public static class LockTimeoutConfigEntry {
        private long timeout;
        private String unit = "SECONDS";
        private String description;

        public long getTimeout() {
            return timeout;
        }

        public void setTimeout(long timeout) {
            this.timeout = timeout;
        }

        public String getUnit() {
            return unit;
        }

        public void setUnit(String unit) {
            this.unit = unit;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public long getTimeoutMillis() {
            switch (unit.toUpperCase()) {
                case "MILLISECONDS":
                    return timeout;
                case "SECONDS":
                    return timeout * 1000;
                case "MINUTES":
                    return timeout * 60 * 1000;
                default:
                    return timeout * 1000;
            }
        }
        }
    }
}
