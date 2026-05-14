package com.fooddelivery.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Data
@Configuration
@ConfigurationProperties(prefix = "fooddelivery.lock")
public class LockConfigProperties {

    private Map<String, LockTimeoutConfig> timeouts = new HashMap<>();

    private String defaultUrgency = "normal";

    @Data
    public static class LockTimeoutConfig {
        private long timeout;
        private TimeUnit unit = TimeUnit.SECONDS;
        private String description;
    }

    public LockTimeoutConfig getTimeoutConfig(String urgency) {
        LockTimeoutConfig config = timeouts.get(urgency);
        if (config == null) {
            config = timeouts.get(defaultUrgency);
        }
        return config != null ? config : createDefaultConfig();
    }

    private LockTimeoutConfig createDefaultConfig() {
        LockTimeoutConfig config = new LockTimeoutConfig();
        config.setTimeout(10);
        config.setUnit(TimeUnit.SECONDS);
        config.setDescription("默认配置");
        return config;
    }

    public boolean isValidUrgency(String urgency) {
        return timeouts.containsKey(urgency);
    }
}
