package com.meeting.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "meeting.reminder")
public class ReminderConfig {

    private boolean enabled = true;
    private int maxReminderCount = 3;
    private long reminderIntervalMinutes = 30;
    private Map<String, ReminderStrategyConfig> strategyConfigs = new HashMap<>();

    @Data
    public static class ReminderStrategyConfig {
        private int requiredConfirmCount = 1;
        private int maxReminderCount = 3;
        private long reminderIntervalMinutes = 30;
        private String description;
    }

    public ReminderStrategyConfig getStrategyForImportance(String importance) {
        if (importance == null || importance.isEmpty()) {
            return getDefaultStrategy();
        }
        return strategyConfigs.getOrDefault(importance, getDefaultStrategy());
    }

    public ReminderStrategyConfig getDefaultStrategy() {
        ReminderStrategyConfig defaultStrategy = new ReminderStrategyConfig();
        defaultStrategy.setRequiredConfirmCount(1);
        defaultStrategy.setMaxReminderCount(maxReminderCount);
        defaultStrategy.setReminderIntervalMinutes(reminderIntervalMinutes);
        defaultStrategy.setDescription("默认提醒策略");
        return defaultStrategy;
    }

    public int getRequiredConfirmCount(String importance) {
        return getStrategyForImportance(importance).getRequiredConfirmCount();
    }

    public int getMaxReminderCount(String importance) {
        return getStrategyForImportance(importance).getMaxReminderCount();
    }

    public long getReminderIntervalMinutes(String importance) {
        return getStrategyForImportance(importance).getReminderIntervalMinutes();
    }

    public boolean hasStrategy(String importance) {
        return importance != null && strategyConfigs.containsKey(importance);
    }

    public void addOrUpdateStrategy(String importance, ReminderStrategyConfig config) {
        strategyConfigs.put(importance, config);
    }

    public void removeStrategy(String importance) {
        strategyConfigs.remove(importance);
    }
}
