package com.travelbooking.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

public class IdGenerator {

    private static final AtomicInteger routeCounter = new AtomicInteger(1);
    private static final AtomicInteger bookingCounter = new AtomicInteger(1);
    private static final AtomicInteger touristCounter = new AtomicInteger(1);
    private static final AtomicInteger itineraryCounter = new AtomicInteger(1);
    private static final AtomicInteger guideCounter = new AtomicInteger(1);
    private static final AtomicInteger spotCounter = new AtomicInteger(1);
    private static final AtomicInteger teamCounter = new AtomicInteger(1);
    private static final AtomicInteger settlementCounter = new AtomicInteger(1);
    private static final AtomicInteger statCounter = new AtomicInteger(1);

    private static String getTimestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
    }

    public static String generateRouteId() {
        return "route_" + getTimestamp() + "_" + String.format("%03d", routeCounter.getAndIncrement());
    }

    public static String generateBookingId() {
        return "booking_" + getTimestamp() + "_" + String.format("%03d", bookingCounter.getAndIncrement());
    }

    public static String generateTouristId() {
        return "tourist_" + getTimestamp() + "_" + String.format("%03d", touristCounter.getAndIncrement());
    }

    public static String generateItineraryId() {
        return "itinerary_" + getTimestamp() + "_" + String.format("%03d", itineraryCounter.getAndIncrement());
    }

    public static String generateGuideId() {
        return "guide_" + getTimestamp() + "_" + String.format("%03d", guideCounter.getAndIncrement());
    }

    public static String generateSpotId() {
        return "spot_" + getTimestamp() + "_" + String.format("%03d", spotCounter.getAndIncrement());
    }

    public static String generateTeamId() {
        return "team_" + getTimestamp() + "_" + String.format("%03d", teamCounter.getAndIncrement());
    }

    public static String generateSettlementId() {
        return "settlement_" + getTimestamp() + "_" + String.format("%03d", settlementCounter.getAndIncrement());
    }

    public static String generateStatId() {
        return "stat_" + getTimestamp() + "_" + String.format("%03d", statCounter.getAndIncrement());
    }
}
