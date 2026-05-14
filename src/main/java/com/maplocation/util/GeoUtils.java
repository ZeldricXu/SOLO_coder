package com.maplocation.util;

import com.maplocation.model.Coordinates;

public class GeoUtils {

    private static final double EARTH_RADIUS = 6371000;

    public static double calculateDistance(Coordinates from, Coordinates to) {
        if (from == null || to == null) {
            return 0;
        }

        double dLat = Math.toRadians(to.getLat() - from.getLat());
        double dLng = Math.toRadians(to.getLng() - from.getLng());

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(from.getLat())) * Math.cos(Math.toRadians(to.getLat())) *
                   Math.sin(dLng / 2) * Math.sin(dLng / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return EARTH_RADIUS * c;
    }

    public static boolean isWithinRadius(Coordinates center, Coordinates point, double radius) {
        return calculateDistance(center, point) <= radius;
    }

    public static boolean isValidCoordinates(Coordinates coords) {
        if (coords == null) {
            return false;
        }
        return coords.getLat() >= -90 && coords.getLat() <= 90 &&
               coords.getLng() >= -180 && coords.getLng() <= 180;
    }
}
