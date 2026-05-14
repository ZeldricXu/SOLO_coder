package com.travelbooking.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "itinerary.reminder")
public class ItineraryReminderConfig {

    private int longTripMinDays = 7;
    private int longTripReminderDays = 3;
    private int shortTripMinDays = 3;
    private int shortTripReminderDays = 1;

    private Map<String, ReminderTypeConfig> types = new HashMap<>();

    public int getReminderDays(int tripDuration) {
        if (tripDuration >= longTripMinDays) {
            return longTripReminderDays;
        }
        return shortTripReminderDays;
    }

    public int getReminderDaysByType(String type) {
        if (types != null && types.containsKey(type)) {
            return types.get(type).getReminderDays();
        }
        return shortTripReminderDays;
    }

    public ReminderTypeConfig getConfigByDuration(int duration) {
        if (duration >= longTripMinDays) {
            return new ReminderTypeConfig("LONG_TRIP", longTripMinDays, longTripReminderDays);
        }
        return new ReminderTypeConfig("SHORT_TRIP", shortTripMinDays, shortTripReminderDays);
    }

    public ReminderTypeConfig getLongTripConfig() {
        return new ReminderTypeConfig("LONG_TRIP", longTripMinDays, longTripReminderDays);
    }

    public ReminderTypeConfig getShortTripConfig() {
        return new ReminderTypeConfig("SHORT_TRIP", shortTripMinDays, shortTripReminderDays);
    }

    @Data
    public static class ReminderTypeConfig {
        private String type;
        private int minDays;
        private int reminderDays;

        public ReminderTypeConfig() {
        }

        public ReminderTypeConfig(String type, int minDays, int reminderDays) {
            this.type = type;
            this.minDays = minDays;
            this.reminderDays = reminderDays;
        }
    }
}
