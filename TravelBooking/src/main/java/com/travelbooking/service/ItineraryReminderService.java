package com.travelbooking.service;

import com.travelbooking.config.ItineraryReminderConfig;
import com.travelbooking.model.Itinerary;
import com.travelbooking.model.Route;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ItineraryReminderService {

    private final ItineraryService itineraryService;
    private final RouteService routeService;
    private final ItineraryReminderConfig reminderConfig;

    public static class ReminderRecord {
        private String itineraryId;
        private String message;
        private LocalDate reminderDate;
        private int reminderType;
        private String tripType;
        private int daysBeforeDeparture;

        public ReminderRecord(String itineraryId, String message, LocalDate reminderDate, 
                              int reminderType, String tripType, int daysBeforeDeparture) {
            this.itineraryId = itineraryId;
            this.message = message;
            this.reminderDate = reminderDate;
            this.reminderType = reminderType;
            this.tripType = tripType;
            this.daysBeforeDeparture = daysBeforeDeparture;
        }

        public String getItineraryId() { return itineraryId; }
        public String getMessage() { return message; }
        public LocalDate getReminderDate() { return reminderDate; }
        public int getReminderType() { return reminderType; }
        public String getTripType() { return tripType; }
        public int getDaysBeforeDeparture() { return daysBeforeDeparture; }
    }

    public ItineraryReminderConfig.ReminderTypeConfig determineItineraryType(Route route) {
        if (route == null || route.getRouteDuration() == null) {
            return reminderConfig.getShortTripConfig();
        }
        return reminderConfig.getConfigByDuration(route.getRouteDuration());
    }

    public String getItineraryTypeLabel(Route route) {
        if (route == null || route.getRouteDuration() == null) {
            return "SHORT_TRIP";
        }
        return route.getRouteDuration() >= reminderConfig.getLongTripMinDays() 
                ? "LONG_TRIP" 
                : "SHORT_TRIP";
    }

    public int getReminderDaysBefore(Itinerary itinerary) {
        if (itinerary.getRouteId() == null) {
            return reminderConfig.getShortTripReminderDays();
        }

        Optional<Route> routeOpt = routeService.getRouteById(itinerary.getRouteId());
        if (routeOpt.isEmpty()) {
            return reminderConfig.getShortTripReminderDays();
        }

        ItineraryReminderConfig.ReminderTypeConfig typeConfig = determineItineraryType(routeOpt.get());
        return typeConfig.getReminderDays();
    }

    public int getReminderDaysByDuration(int duration) {
        return reminderConfig.getReminderDays(duration);
    }

    public boolean shouldTriggerReminder(Itinerary itinerary) {
        if (itinerary.getItineraryStart() == null) {
            return false;
        }

        if (!"pending_departure".equals(itinerary.getItineraryStatus())) {
            return false;
        }

        int reminderDays = getReminderDaysBefore(itinerary);
        LocalDate today = LocalDate.now();
        long daysUntilDeparture = ChronoUnit.DAYS.between(today, itinerary.getItineraryStart());

        log.debug("检查提醒 - 行程ID: {}, 出发日期: {}, 今天: {}, 离出发天数: {}, 配置提醒天数: {}", 
                itinerary.getItineraryId(), itinerary.getItineraryStart(), today, 
                daysUntilDeparture, reminderDays);

        return daysUntilDeparture == reminderDays || daysUntilDeparture == 0;
    }

    public List<ReminderRecord> checkAndGenerateReminders(List<Itinerary> itineraries) {
        List<ReminderRecord> reminders = new ArrayList<>();

        for (Itinerary itinerary : itineraries) {
            if (shouldTriggerReminder(itinerary)) {
                int reminderDays = getReminderDaysBefore(itinerary);
                LocalDate today = LocalDate.now();
                long daysUntilDeparture = ChronoUnit.DAYS.between(today, itinerary.getItineraryStart());

                String tripType = getItineraryTypeLabel(getRouteById(itinerary.getRouteId()));

                String message;
                int reminderType;

                if (daysUntilDeparture == reminderDays) {
                    message = String.format("您的%s将在%d天后出发，请做好准备！行程ID: %s",
                            "LONG_TRIP".equals(tripType) ? "长行程" : "短行程",
                            daysUntilDeparture, itinerary.getItineraryId());
                    reminderType = 1;
                } else if (daysUntilDeparture == 0) {
                    message = String.format("您的行程今天出发！请准时集合。行程ID: %s",
                            itinerary.getItineraryId());
                    reminderType = 2;
                } else {
                    continue;
                }

                log.info("生成提醒 - 行程ID: {}, 类型: {}, 出发天数: {}, 消息: {}", 
                        itinerary.getItineraryId(), tripType, daysUntilDeparture, message);

                reminders.add(new ReminderRecord(
                        itinerary.getItineraryId(),
                        message,
                        today,
                        reminderType,
                        tripType,
                        (int) daysUntilDeparture
                ));
            }
        }

        return reminders;
    }

    private Route getRouteById(String routeId) {
        if (routeId == null) return null;
        return routeService.getRouteById(routeId).orElse(null);
    }

    public List<ReminderRecord> checkAllPendingItineraries() {
        List<Itinerary> allItineraries = itineraryService.getAllItineraries();
        List<Itinerary> pendingItineraries = allItineraries.stream()
                .filter(it -> "pending_departure".equals(it.getItineraryStatus()))
                .toList();
        return checkAndGenerateReminders(pendingItineraries);
    }

    public ItineraryReminderConfig getReminderConfig() {
        return reminderConfig;
    }

    public void refreshConfig() {
        log.info("行程提醒配置已刷新 - 长行程: {}天前提醒, 短行程: {}天前提醒",
                reminderConfig.getLongTripReminderDays(),
                reminderConfig.getShortTripReminderDays());
    }
}
