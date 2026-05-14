package com.schedulebook.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "schedulebook.reminder")
public class ReminderIntervalConfig {

    private Map<String, List<ReminderRule>> rules = new HashMap<>();

    public ReminderIntervalConfig() {
        List<ReminderRule> shortDurationRules = new ArrayList<>();
        shortDurationRules.add(new ReminderRule("before_30min", 30, "sms"));
        shortDurationRules.add(new ReminderRule("before_15min", 15, "sms"));
        rules.put("short", shortDurationRules);

        List<ReminderRule> mediumDurationRules = new ArrayList<>();
        mediumDurationRules.add(new ReminderRule("before_2hour", 120, "email"));
        mediumDurationRules.add(new ReminderRule("before_1hour", 60, "sms"));
        mediumDurationRules.add(new ReminderRule("before_30min", 30, "sms"));
        rules.put("medium", mediumDurationRules);

        List<ReminderRule> longDurationRules = new ArrayList<>();
        longDurationRules.add(new ReminderRule("before_day", 1440, "email"));
        longDurationRules.add(new ReminderRule("before_2hour", 120, "email"));
        rules.put("long", longDurationRules);

        List<ReminderRule> allDayRules = new ArrayList<>();
        allDayRules.add(new ReminderRule("before_day", 1440, "email"));
        allDayRules.add(new ReminderRule("before_2hour", 120, "sms"));
        rules.put("all_day", allDayRules);
    }

    public Map<String, List<ReminderRule>> getRules() {
        return rules;
    }

    public void setRules(Map<String, List<ReminderRule>> rules) {
        this.rules = rules;
    }

    public List<ReminderRule> getRulesForDuration(int durationMinutes) {
        String category = getDurationCategory(durationMinutes);
        return rules.getOrDefault(category, rules.get("medium"));
    }

    private String getDurationCategory(int durationMinutes) {
        if (durationMinutes <= 30) {
            return "short";
        } else if (durationMinutes <= 120) {
            return "medium";
        } else if (durationMinutes <= 480) {
            return "long";
        } else {
            return "all_day";
        }
    }

    public static class ReminderRule {
        private String type;
        private int minutesBefore;
        private String channel;

        public ReminderRule() {
        }

        public ReminderRule(String type, int minutesBefore, String channel) {
            this.type = type;
            this.minutesBefore = minutesBefore;
            this.channel = channel;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public int getMinutesBefore() {
            return minutesBefore;
        }

        public void setMinutesBefore(int minutesBefore) {
            this.minutesBefore = minutesBefore;
        }

        public String getChannel() {
            return channel;
        }

        public void setChannel(String channel) {
            this.channel = channel;
        }
    }
}
