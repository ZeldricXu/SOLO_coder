package com.logistics.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "logistics.lock")
public class CourierLockConfig {

    private Map<String, Long> timeoutSeconds = new HashMap<>();

    public long getTimeoutSeconds(String urgencyLevel) {
        Long timeout = timeoutSeconds.get(urgencyLevel);
        if (timeout == null) {
            return 30L;
        }
        return timeout;
    }

    public Map<String, Long> getAllTimeouts() {
        return new HashMap<>(timeoutSeconds);
    }

    public void updateTimeout(String urgencyLevel, long timeoutSeconds) {
        this.timeoutSeconds.put(urgencyLevel, timeoutSeconds);
    }

    public void removeTimeout(String urgencyLevel) {
        this.timeoutSeconds.remove(urgencyLevel);
    }
}
