package com.edgeplatform.common.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class TimeUtils {

    public static final String ISO_DATETIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss'Z'";
    public static final ZoneId UTC_ZONE = ZoneId.of("UTC");

    private TimeUtils() {
    }

    public static LocalDateTime nowUtc() {
        return LocalDateTime.now(UTC_ZONE);
    }

    public static String toIsoString(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        ZonedDateTime zoned = dateTime.atZone(UTC_ZONE);
        return zoned.format(DateTimeFormatter.ofPattern(ISO_DATETIME_FORMAT));
    }

    public static LocalDateTime fromIsoString(String isoString) {
        if (isoString == null || isoString.isEmpty()) {
            return null;
        }
        Instant instant = Instant.parse(isoString);
        return LocalDateTime.ofInstant(instant, UTC_ZONE);
    }

    public static long toEpochMillis(LocalDateTime dateTime) {
        return dateTime.atZone(UTC_ZONE).toInstant().toEpochMilli();
    }

    public static LocalDateTime fromEpochMillis(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), UTC_ZONE);
    }
}
