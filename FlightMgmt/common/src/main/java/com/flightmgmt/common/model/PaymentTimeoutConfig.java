package com.flightmgmt.common.model;

import java.util.HashMap;
import java.util.Map;

public class PaymentTimeoutConfig {
    private String configId;
    private Map<String, Integer> timeoutMinutesByType;
    private int maxRetryCount;
    private int retryIntervalSeconds;
    private boolean enabled;

    public PaymentTimeoutConfig() {
        this.timeoutMinutesByType = new HashMap<>();
        this.maxRetryCount = 3;
        this.retryIntervalSeconds = 60;
        this.enabled = true;
    }

    public static PaymentTimeoutConfig createDefault() {
        PaymentTimeoutConfig config = new PaymentTimeoutConfig();
        config.setConfigId("default_payment_timeout");
        config.timeoutMinutesByType.put("domestic", 15);
        config.timeoutMinutesByType.put("international", 30);
        config.setMaxRetryCount(3);
        config.setRetryIntervalSeconds(60);
        config.setEnabled(true);
        return config;
    }

    public int getTimeoutMinutes(String flightType) {
        if (flightType == null) {
            return timeoutMinutesByType.getOrDefault("domestic", 15);
        }
        return timeoutMinutesByType.getOrDefault(flightType.toLowerCase(), 15);
    }

    public void setTimeoutMinutes(String flightType, int minutes) {
        timeoutMinutesByType.put(flightType.toLowerCase(), minutes);
    }

    public String getConfigId() { return configId; }
    public void setConfigId(String configId) { this.configId = configId; }
    public Map<String, Integer> getTimeoutMinutesByType() { return timeoutMinutesByType; }
    public void setTimeoutMinutesByType(Map<String, Integer> timeoutMinutesByType) { this.timeoutMinutesByType = timeoutMinutesByType; }
    public int getMaxRetryCount() { return maxRetryCount; }
    public void setMaxRetryCount(int maxRetryCount) { this.maxRetryCount = maxRetryCount; }
    public int getRetryIntervalSeconds() { return retryIntervalSeconds; }
    public void setRetryIntervalSeconds(int retryIntervalSeconds) { this.retryIntervalSeconds = retryIntervalSeconds; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
