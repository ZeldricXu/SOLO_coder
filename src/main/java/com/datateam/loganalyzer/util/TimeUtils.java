package com.datateam.loganalyzer.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

public class TimeUtils {

    private static final List<DateTimeFormatter> FORMATTERS = new ArrayList<>();
    private static final ZoneId DEFAULT_ZONE = ZoneId.systemDefault();

    static {
        FORMATTERS.add(DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.of("UTC")));
        FORMATTERS.add(DateTimeFormatter.ISO_DATE_TIME.withZone(ZoneId.of("UTC")));
        FORMATTERS.add(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        FORMATTERS.add(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ"));
        FORMATTERS.add(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX"));
        FORMATTERS.add(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
        FORMATTERS.add(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        FORMATTERS.add(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss.SSS"));
        FORMATTERS.add(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"));
        FORMATTERS.add(DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z"));
        FORMATTERS.add(DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm:ss"));
        FORMATTERS.add(DateTimeFormatter.ofPattern("MMM  d HH:mm:ss"));
        FORMATTERS.add(DateTimeFormatter.ofPattern("MMM dd HH:mm:ss"));
        FORMATTERS.add(DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss zzz yyyy"));
    }

    public static Instant parseTimestamp(String timestampStr) {
        return parseTimestamp(timestampStr, DEFAULT_ZONE);
    }

    public static Instant parseTimestamp(String timestampStr, ZoneId targetZone) {
        if (timestampStr == null || timestampStr.trim().isEmpty()) {
            return null;
        }

        String trimmed = timestampStr.trim();

        try {
            long epochMillis = Long.parseLong(trimmed);
            if (epochMillis > 1000000000000L) {
                return Instant.ofEpochMilli(epochMillis);
            } else {
                return Instant.ofEpochSecond(epochMillis);
            }
        } catch (NumberFormatException ignored) {
        }

        for (DateTimeFormatter formatter : FORMATTERS) {
            try {
                return parseWithFormatter(trimmed, formatter, targetZone);
            } catch (DateTimeParseException ignored) {
            }
        }

        String cleaned = cleanedTimestamp(trimmed);
        for (DateTimeFormatter formatter : FORMATTERS) {
            try {
                return parseWithFormatter(cleaned, formatter, targetZone);
            } catch (DateTimeParseException ignored) {
            }
        }

        return null;
    }

    private static Instant parseWithFormatter(String str, DateTimeFormatter formatter, ZoneId targetZone) {
        try {
            ZonedDateTime zdt = ZonedDateTime.parse(str, formatter);
            return zdt.withZoneSameInstant(targetZone).toInstant();
        } catch (DateTimeParseException e1) {
            try {
                LocalDateTime ldt = LocalDateTime.parse(str, formatter);
                return ZonedDateTime.of(ldt, DEFAULT_ZONE).withZoneSameInstant(targetZone).toInstant();
            } catch (DateTimeParseException e2) {
                throw e2;
            }
        }
    }

    private static String cleanedTimestamp(String ts) {
        if (ts.startsWith("[") && ts.endsWith("]")) {
            return ts.substring(1, ts.length() - 1);
        }
        ts = ts.replaceAll("^\"|\"$", "");
        ts = ts.replaceAll(",\\d+$", "");
        return ts.trim();
    }

    public static Instant truncateToGranularity(Instant instant, Granularity granularity) {
        ZonedDateTime zdt = ZonedDateTime.ofInstant(instant, DEFAULT_ZONE);
        switch (granularity) {
            case SECOND:
                return zdt.withNano(0).toInstant();
            case MINUTE:
                return zdt.withSecond(0).withNano(0).toInstant();
            case HOUR:
                return zdt.withMinute(0).withSecond(0).withNano(0).toInstant();
            case DAY:
                return zdt.withHour(0).withMinute(0).withSecond(0).withNano(0).toInstant();
            default:
                return instant;
        }
    }

    public static long getGranularitySeconds(Granularity granularity) {
        switch (granularity) {
            case SECOND: return 1;
            case MINUTE: return 60;
            case HOUR: return 3600;
            case DAY: return 86400;
            default: return 60;
        }
    }

    public enum Granularity {
        SECOND,
        MINUTE,
        HOUR,
        DAY
    }

    public static String formatInstant(Instant instant) {
        return formatInstant(instant, "yyyy-MM-dd HH:mm:ss");
    }

    public static String formatInstant(Instant instant, String pattern) {
        if (instant == null) return "";
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern).withZone(DEFAULT_ZONE);
        return formatter.format(instant);
    }
}
