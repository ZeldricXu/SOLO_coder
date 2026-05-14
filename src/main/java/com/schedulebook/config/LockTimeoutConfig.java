package com.schedulebook.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "schedulebook.lock")
public class LockTimeoutConfig {

    private Map<String, Long> timeoutSeconds = new HashMap<>();

    public LockTimeoutConfig() {
        timeoutSeconds.put("urgent", 5L);
        timeoutSeconds.put("normal", 30L);
        timeoutSeconds.put("low", 15L);
    }

    public Map<String, Long> getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Map<String, Long> timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public long getTimeoutForUrgency(String urgencyLevel) {
        return timeoutSeconds.getOrDefault(urgencyLevel, 30L);
    }

    public boolean isValidUrgencyLevel(String urgencyLevel) {
        return timeoutSeconds.containsKey(urgencyLevel);
    }
}
