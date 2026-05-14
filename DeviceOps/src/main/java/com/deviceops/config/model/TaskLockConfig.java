package com.deviceops.config.model;

public class TaskLockConfig {

    private String priorityLevel;
    private Integer lockTimeoutSeconds;
    private Integer maxWaitTimeSeconds;
    private Boolean enablePriorityBoost;

    public TaskLockConfig() {
    }

    public TaskLockConfig(String priorityLevel, Integer lockTimeoutSeconds,
                          Integer maxWaitTimeSeconds, Boolean enablePriorityBoost) {
        this.priorityLevel = priorityLevel;
        this.lockTimeoutSeconds = lockTimeoutSeconds;
        this.maxWaitTimeSeconds = maxWaitTimeSeconds;
        this.enablePriorityBoost = enablePriorityBoost;
    }

    public String getPriorityLevel() {
        return priorityLevel;
    }

    public void setPriorityLevel(String priorityLevel) {
        this.priorityLevel = priorityLevel;
    }

    public Integer getLockTimeoutSeconds() {
        return lockTimeoutSeconds;
    }

    public void setLockTimeoutSeconds(Integer lockTimeoutSeconds) {
        this.lockTimeoutSeconds = lockTimeoutSeconds;
    }

    public Integer getMaxWaitTimeSeconds() {
        return maxWaitTimeSeconds;
    }

    public void setMaxWaitTimeSeconds(Integer maxWaitTimeSeconds) {
        this.maxWaitTimeSeconds = maxWaitTimeSeconds;
    }

    public Boolean getEnablePriorityBoost() {
        return enablePriorityBoost;
    }

    public void setEnablePriorityBoost(Boolean enablePriorityBoost) {
        this.enablePriorityBoost = enablePriorityBoost;
    }
}
