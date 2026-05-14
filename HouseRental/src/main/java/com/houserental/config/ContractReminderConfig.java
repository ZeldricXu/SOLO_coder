package com.houserental.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "contract.reminder")
public class ContractReminderConfig {

    private List<ReminderTypeConfig> types = new ArrayList<>();
    private int longTermDaysBefore = 60;
    private int shortTermDaysBefore = 14;
    private int longTermThresholdMonths = 12;

    @Data
    public static class ReminderTypeConfig {
        private String type;
        private String name;
        private int thresholdMonths;
        private int daysBefore;
        private int frequency;
        private String description;
    }

    public ReminderTypeConfig getTypeConfig(String type) {
        return types.stream()
                .filter(t -> type.equals(t.getType()))
                .findFirst()
                .orElse(null);
    }

    public ReminderTypeConfig getTypeConfigByMonths(int months) {
        List<ReminderTypeConfig> sorted = types.stream()
                .sorted((a, b) -> Integer.compare(b.getThresholdMonths(), a.getThresholdMonths()))
                .toList();
        
        for (ReminderTypeConfig config : sorted) {
            if (months >= config.getThresholdMonths()) {
                return config;
            }
        }
        
        return sorted.isEmpty() ? null : sorted.get(sorted.size() - 1);
    }

    public int getReminderDaysBefore(String type) {
        ReminderTypeConfig config = getTypeConfig(type);
        return config != null ? config.getDaysBefore() : shortTermDaysBefore;
    }

    public int getReminderFrequency(String type) {
        ReminderTypeConfig config = getTypeConfig(type);
        return config != null ? config.getFrequency() : 1;
    }

    public List<String> getAllTypes() {
        return types.stream()
                .map(ReminderTypeConfig::getType)
                .toList();
    }

    public String determineContractType(int months) {
        ReminderTypeConfig config = getTypeConfigByMonths(months);
        return config != null ? config.getType() : "short_term";
    }
}
