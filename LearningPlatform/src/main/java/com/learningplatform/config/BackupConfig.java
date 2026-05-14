package com.learningplatform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "learning.backup")
public class BackupConfig {

    private Activity activity = new Activity();
    private Frequency frequency = new Frequency();
    private int cleanupDaysToKeep = 30;

    @Data
    public static class Activity {
        private Map<String, Integer> threshold = new HashMap<>();
        private int windowMinutes = 60;

        public int getHighThreshold() {
            return threshold.getOrDefault("high", 10);
        }

        public int getMediumThreshold() {
            return threshold.getOrDefault("medium", 5);
        }

        public int getLowThreshold() {
            return threshold.getOrDefault("low", 1);
        }
    }

    @Data
    public static class Frequency {
        private Map<String, Integer> intervalMinutes = new HashMap<>();

        public int getHighIntervalMinutes() {
            return intervalMinutes.getOrDefault("high", 1);
        }

        public int getMediumIntervalMinutes() {
            return intervalMinutes.getOrDefault("medium", 5);
        }

        public int getLowIntervalMinutes() {
            return intervalMinutes.getOrDefault("low", 30);
        }

        public Duration getHighInterval() {
            return Duration.ofMinutes(getHighIntervalMinutes());
        }

        public Duration getMediumInterval() {
            return Duration.ofMinutes(getMediumIntervalMinutes());
        }

        public Duration getLowInterval() {
            return Duration.ofMinutes(getLowIntervalMinutes());
        }

        public Duration getIntervalByLevel(String level) {
            switch (level) {
                case "high":
                    return getHighInterval();
                case "medium":
                    return getMediumInterval();
                default:
                    return getLowInterval();
            }
        }
    }

    public String determineBackupLevel(int activityCount) {
        if (activityCount >= activity.getHighThreshold()) {
            return "high";
        } else if (activityCount >= activity.getMediumThreshold()) {
            return "medium";
        } else {
            return "low";
        }
    }

    public Duration getBackupIntervalByActivity(int activityCount) {
        String level = determineBackupLevel(activityCount);
        return frequency.getIntervalByLevel(level);
    }

    public Duration getActivityWindow() {
        return Duration.ofMinutes(activity.getWindowMinutes());
    }
}
