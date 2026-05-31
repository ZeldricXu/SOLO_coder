package com.monitoring.common.utils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class TimeUtils {

    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT;

    private TimeUtils() {
    }

    public static Instant now() {
        return Instant.now();
    }

    public static String formatInstant(Instant instant) {
        return ISO_FORMATTER.format(instant);
    }

    public static Instant parseInstant(String str) {
        return Instant.parse(str);
    }

    public static long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    public static long toEpochMilli(Instant instant) {
        return instant.toEpochMilli();
    }

    public static Instant fromEpochMilli(long millis) {
        return Instant.ofEpochMilli(millis);
    }

    public static LocalDateTime toLocalDateTime(Instant instant, ZoneId zoneId) {
        return LocalDateTime.ofInstant(instant, zoneId);
    }

    public static ZonedDateTime toZonedDateTime(Instant instant, ZoneId zoneId) {
        return ZonedDateTime.ofInstant(instant, zoneId);
    }
}
