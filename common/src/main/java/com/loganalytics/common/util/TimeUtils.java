package com.loganalytics.common.util;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

public final class TimeUtils {
    public static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT;
    public static final DateTimeFormatter DATE_BUCKET_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private static final List<DateTimeFormatter> TIMESTAMP_PARSERS;

    static {
        TIMESTAMP_PARSERS = new ArrayList<>();
        TIMESTAMP_PARSERS.add(DateTimeFormatter.ISO_INSTANT);
        TIMESTAMP_PARSERS.add(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        TIMESTAMP_PARSERS.add(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        TIMESTAMP_PARSERS.add(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
        TIMESTAMP_PARSERS.add(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"));
        TIMESTAMP_PARSERS.add(DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm:ss"));
        TIMESTAMP_PARSERS.add(DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z"));
    }

    private TimeUtils() {}

    public static Instant parseTimestamp(String timestampStr) {
        if (timestampStr == null || timestampStr.isBlank()) {
            return Instant.now();
        }

        try {
            long epoch = Long.parseLong(timestampStr.trim());
            if (epoch > 1_000_000_000_000L) {
                return Instant.ofEpochMilli(epoch);
            } else {
                return Instant.ofEpochSecond(epoch);
            }
        } catch (NumberFormatException ignored) {
        }

        for (DateTimeFormatter formatter : TIMESTAMP_PARSERS) {
            try {
                return Instant.from(formatter.parse(timestampStr.trim()));
            } catch (DateTimeParseException ignored) {
            }
        }

        return Instant.now();
    }

    public static String getDateBucket(Instant instant) {
        return DATE_BUCKET_FORMATTER.format(LocalDateTime.ofInstant(instant, ZoneId.of("UTC")));
    }

    public static Instant getWindowStart(Instant timestamp, Duration windowSize) {
        long windowMillis = windowSize.toMillis();
        long tsMillis = timestamp.toEpochMilli();
        return Instant.ofEpochMilli((tsMillis / windowMillis) * windowMillis);
    }

    public static Instant getWindowEnd(Instant timestamp, Duration windowSize) {
        return getWindowStart(timestamp, windowSize).plus(windowSize);
    }

    public static List<Instant> generateWindows(Instant start, Instant end, Duration step) {
        List<Instant> windows = new ArrayList<>();
        Instant current = getWindowStart(start, step);
        while (current.isBefore(end)) {
            windows.add(current);
            current = current.plus(step);
        }
        return windows;
    }

    public static Duration parseDuration(String durationStr) {
        if (durationStr == null || durationStr.isBlank()) {
            throw new IllegalArgumentException("Duration string cannot be null or blank");
        }

        String s = durationStr.trim().toLowerCase();
        try {
            if (s.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(s.substring(0, s.length() - 2)));
            } else if (s.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(s.substring(0, s.length() - 1)));
            } else if (s.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(s.substring(0, s.length() - 1)));
            } else if (s.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(s.substring(0, s.length() - 1)));
            } else if (s.endsWith("d")) {
                return Duration.ofDays(Long.parseLong(s.substring(0, s.length() - 1)));
            } else {
                return Duration.ofSeconds(Long.parseLong(s));
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid duration format: " + durationStr, e);
        }
    }
}
