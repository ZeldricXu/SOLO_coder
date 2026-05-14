package com.homeservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.util.HashMap;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "homeservice.customer-level")
public class CustomerLevelConfig {

    private Map<String, LockTimeoutConfig> lockTimeout = new HashMap<>();
    private Map<String, ReminderConfig> reminder = new HashMap<>();

    public Map<String, LockTimeoutConfig> getLockTimeout() {
        return lockTimeout;
    }

    public void setLockTimeout(Map<String, LockTimeoutConfig> lockTimeout) {
        this.lockTimeout = lockTimeout;
    }

    public Map<String, ReminderConfig> getReminder() {
        return reminder;
    }

    public void setReminder(Map<String, ReminderConfig> reminder) {
        this.reminder = reminder;
    }

    public LockTimeoutConfig getLockTimeoutByLevel(String levelCode) {
        return lockTimeout.getOrDefault(levelCode, lockTimeout.getOrDefault("default", 
            new LockTimeoutConfig(30000L, 5000L)));
    }

    public ReminderConfig getReminderByLevel(String levelCode) {
        return reminder.getOrDefault(levelCode, reminder.getOrDefault("default",
            new ReminderConfig(24, 3, 6)));
    }

    public static class LockTimeoutConfig {
        private Long waitTimeoutMs;
        private Long holdTimeoutMs;

        public LockTimeoutConfig() {}

        public LockTimeoutConfig(Long waitTimeoutMs, Long holdTimeoutMs) {
            this.waitTimeoutMs = waitTimeoutMs;
            this.holdTimeoutMs = holdTimeoutMs;
        }

        public Long getWaitTimeoutMs() {
            return waitTimeoutMs;
        }

        public void setWaitTimeoutMs(Long waitTimeoutMs) {
            this.waitTimeoutMs = waitTimeoutMs;
        }

        public Long getHoldTimeoutMs() {
            return holdTimeoutMs;
        }

        public void setHoldTimeoutMs(Long holdTimeoutMs) {
            this.holdTimeoutMs = holdTimeoutMs;
        }
    }

    public static class ReminderConfig {
        private Integer intervalHours;
        private Integer maxReminders;
        private Integer activityThreshold;

        public ReminderConfig() {}

        public ReminderConfig(Integer intervalHours, Integer maxReminders, Integer activityThreshold) {
            this.intervalHours = intervalHours;
            this.maxReminders = maxReminders;
            this.activityThreshold = activityThreshold;
        }

        public Integer getIntervalHours() {
            return intervalHours;
        }

        public void setIntervalHours(Integer intervalHours) {
            this.intervalHours = intervalHours;
        }

        public Integer getMaxReminders() {
            return maxReminders;
        }

        public void setMaxReminders(Integer maxReminders) {
            this.maxReminders = maxReminders;
        }

        public Integer getActivityThreshold() {
            return activityThreshold;
        }

        public void setActivityThreshold(Integer activityThreshold) {
            this.activityThreshold = activityThreshold;
        }
    }
}
