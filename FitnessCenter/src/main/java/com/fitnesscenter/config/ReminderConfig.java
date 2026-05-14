package com.fitnesscenter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "fitness.reminder")
public class ReminderConfig {

    private List<FrequencyRule> rules = new ArrayList<>();

    private static final int DEFAULT_ACTIVE_THRESHOLD = 3;
    private static final int DEFAULT_ACTIVE_FREQUENCY_DAYS = 7;
    private static final int DEFAULT_INACTIVE_FREQUENCY_DAYS = 2;

    public List<FrequencyRule> getRules() {
        if (rules.isEmpty()) {
            initDefaultRules();
        }
        return rules;
    }

    public void setRules(List<FrequencyRule> rules) {
        this.rules = rules;
    }

    private void initDefaultRules() {
        FrequencyRule superActive = new FrequencyRule();
        superActive.setName("super-active");
        superActive.setThreshold(10);
        superActive.setFrequencyDays(14);
        superActive.setDescription("超活跃会员 - 极少提醒");

        FrequencyRule active = new FrequencyRule();
        active.setName("active");
        active.setThreshold(DEFAULT_ACTIVE_THRESHOLD);
        active.setFrequencyDays(DEFAULT_ACTIVE_FREQUENCY_DAYS);
        active.setDescription("活跃会员 - 低频提醒");

        FrequencyRule inactive = new FrequencyRule();
        inactive.setName("inactive");
        inactive.setThreshold(0);
        inactive.setFrequencyDays(DEFAULT_INACTIVE_FREQUENCY_DAYS);
        inactive.setDescription("不活跃会员 - 高频提醒");

        rules.add(superActive);
        rules.add(active);
        rules.add(inactive);
    }

    public FrequencyRule getRuleByTrainingCount(int trainingCount) {
        List<FrequencyRule> sortedRules = new ArrayList<>(getRules());
        sortedRules.sort((a, b) -> Integer.compare(b.getThreshold(), a.getThreshold()));

        for (FrequencyRule rule : sortedRules) {
            if (trainingCount >= rule.getThreshold()) {
                return rule;
            }
        }

        return sortedRules.get(sortedRules.size() - 1);
    }

    public String getFrequencyType(int trainingCount) {
        return getRuleByTrainingCount(trainingCount).getName();
    }

    public int getFrequencyDays(int trainingCount) {
        return getRuleByTrainingCount(trainingCount).getFrequencyDays();
    }

    public int getActiveThreshold() {
        return DEFAULT_ACTIVE_THRESHOLD;
    }

    public int getActiveFrequencyDays() {
        return DEFAULT_ACTIVE_FREQUENCY_DAYS;
    }

    public int getInactiveFrequencyDays() {
        return DEFAULT_INACTIVE_FREQUENCY_DAYS;
    }

    public static class FrequencyRule {
        private String name;
        private int threshold;
        private int frequencyDays;
        private String description;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getThreshold() {
            return threshold;
        }

        public void setThreshold(int threshold) {
            this.threshold = threshold;
        }

        public int getFrequencyDays() {
            return frequencyDays;
        }

        public void setFrequencyDays(int frequencyDays) {
            this.frequencyDays = frequencyDays;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }
}
