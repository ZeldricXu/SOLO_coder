package com.parking.platform.common.util;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class TimeUtils {

    private static final DateTimeFormatter STANDARD_FORMATTER = DateTimeFormatter.ISO_INSTANT;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static String formatNow() {
        return STANDARD_FORMATTER.format(Instant.now());
    }

    public static String format(Instant instant) {
        return instant != null ? STANDARD_FORMATTER.format(instant) : null;
    }

    public static String formatLocalDateTime(LocalDateTime dateTime) {
        return dateTime != null ? dateTime.format(DATE_TIME_FORMATTER) : null;
    }

    public static Instant parse(String isoString) {
        return Instant.parse(isoString);
    }

    public static long durationMillis(Instant start, Instant end) {
        if (start == null || end == null) {
            return 0;
        }
        return Duration.between(start, end).toMillis();
    }

    public static long elapsedMillis(Instant start) {
        return durationMillis(start, Instant.now());
    }

    public static long elapsedSeconds(Instant start) {
        return Duration.between(start, Instant.now()).getSeconds();
    }

    public static Instant fromMillis(long millis) {
        return Instant.ofEpochMilli(millis);
    }

    public static LocalDateTime toLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    public static Instant fromLocalDateTime(LocalDateTime dateTime) {
        return dateTime.atZone(ZoneId.systemDefault()).toInstant();
    }

    public static boolean isExpired(Instant expiryTime) {
        return expiryTime != null && Instant.now().isAfter(expiryTime);
    }

    public static boolean isWithinRange(Instant time, Instant start, Instant end) {
        return time != null && (start == null || !time.isBefore(start)) && (end == null || !time.isAfter(end));
    }
}
