package com.deviceops.config.model;

public class AlertConfig {

    private String faultLevel;
    private Integer maxRetries;
    private Integer retryIntervalSeconds;
    private Boolean autoRetry;

    public AlertConfig() {
    }

    public AlertConfig(String faultLevel, Integer maxRetries, Integer retryIntervalSeconds, Boolean autoRetry) {
        this.faultLevel = faultLevel;
        this.maxRetries = maxRetries;
        this.retryIntervalSeconds = retryIntervalSeconds;
        this.autoRetry = autoRetry;
    }

    public String getFaultLevel() {
        return faultLevel;
    }

    public void setFaultLevel(String faultLevel) {
        this.faultLevel = faultLevel;
    }

    public Integer getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(Integer maxRetries) {
        this.maxRetries = maxRetries;
    }

    public Integer getRetryIntervalSeconds() {
        return retryIntervalSeconds;
    }

    public void setRetryIntervalSeconds(Integer retryIntervalSeconds) {
        this.retryIntervalSeconds = retryIntervalSeconds;
    }

    public Boolean getAutoRetry() {
        return autoRetry;
    }

    public void setAutoRetry(Boolean autoRetry) {
        this.autoRetry = autoRetry;
    }
}
