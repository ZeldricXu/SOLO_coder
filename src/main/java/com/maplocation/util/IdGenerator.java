package com.maplocation.util;

import java.util.UUID;

public class IdGenerator {

    public static String generateLocationId() {
        return "location_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateRouteId() {
        return "route_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateMarkerId() {
        return "marker_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateDistanceId() {
        return "distance_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateStatId() {
        return "stat_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateNearbyId() {
        return "nearby_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateHistoryId() {
        return "history_" + UUID.randomUUID().toString().substring(0, 8);
    }
}
