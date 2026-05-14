package com.flightmgmt.common.util;

import java.util.UUID;

public class IdGenerator {
    public static String generateFlightId() {
        return "flight_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateBookingId() {
        return "booking_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generatePassengerId() {
        return "passenger_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateStatusId() {
        return "status_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateChangeId() {
        return "change_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateStatId() {
        return "stat_" + UUID.randomUUID().toString().substring(0, 8);
    }
}
