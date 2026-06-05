package com.company.dbstudio.core.util;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Date;

public final class DateUtils {

    public static final String DATE_PATTERN = "yyyy-MM-dd";
    public static final String TIME_PATTERN = "HH:mm:ss";
    public static final String DATETIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    public static final String TIMESTAMP_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS";
    public static final String ISO_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";

    public static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern(DATE_PATTERN);
    public static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern(TIME_PATTERN);
    public static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern(DATETIME_PATTERN);
    public static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern(TIMESTAMP_PATTERN);
    public static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern(ISO_PATTERN);

    private DateUtils() {
    }

    public static LocalDate nowDate() {
        return LocalDate.now();
    }

    public static LocalTime nowTime() {
        return LocalTime.now();
    }

    public static LocalDateTime nowDateTime() {
        return LocalDateTime.now();
    }

    public static ZonedDateTime nowZoned() {
        return ZonedDateTime.now();
    }

    public static Instant nowInstant() {
        return Instant.now();
    }

    public static long nowMillis() {
        return System.currentTimeMillis();
    }

    public static String formatDate(LocalDate date) {
        return date != null ? date.format(DATE_FORMATTER) : null;
    }

    public static String formatTime(LocalTime time) {
        return time != null ? time.format(TIME_FORMATTER) : null;
    }

    public static String formatDateTime(LocalDateTime dateTime) {
        return dateTime != null ? dateTime.format(DATETIME_FORMATTER) : null;
    }

    public static String formatTimestamp(LocalDateTime dateTime) {
        return dateTime != null ? dateTime.format(TIMESTAMP_FORMATTER) : null;
    }

    public static String formatInstant(Instant instant) {
        return instant != null ? ISO_FORMATTER.format(instant.atZone(ZoneId.systemDefault())) : null;
    }

    public static String formatMillis(long millis) {
        return formatInstant(Instant.ofEpochMilli(millis));
    }

    public static LocalDate parseDate(String dateStr) {
        return StringUtils.isBlank(dateStr) ? null : LocalDate.parse(dateStr, DATE_FORMATTER);
    }

    public static LocalTime parseTime(String timeStr) {
        return StringUtils.isBlank(timeStr) ? null : LocalTime.parse(timeStr, TIME_FORMATTER);
    }

    public static LocalDateTime parseDateTime(String dateTimeStr) {
        return StringUtils.isBlank(dateTimeStr) ? null : LocalDateTime.parse(dateTimeStr, DATETIME_FORMATTER);
    }

    public static LocalDateTime parseTimestamp(String timestampStr) {
        return StringUtils.isBlank(timestampStr) ? null : LocalDateTime.parse(timestampStr, TIMESTAMP_FORMATTER);
    }

    public static Instant parseInstant(String isoStr) {
        return StringUtils.isBlank(isoStr) ? null : Instant.parse(isoStr);
    }

    public static LocalDateTime fromDate(Date date) {
        return date != null ? date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime() : null;
    }

    public static Date toDate(LocalDateTime dateTime) {
        return dateTime != null ? Date.from(dateTime.atZone(ZoneId.systemDefault()).toInstant()) : null;
    }

    public static Instant toInstant(LocalDateTime dateTime) {
        return dateTime != null ? dateTime.atZone(ZoneId.systemDefault()).toInstant() : null;
    }

    public static long toMillis(LocalDateTime dateTime) {
        return dateTime != null ? toInstant(dateTime).toEpochMilli() : 0;
    }

    public static long daysBetween(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            return 0;
        }
        return ChronoUnit.DAYS.between(start, end);
    }

    public static long hoursBetween(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return 0;
        }
        return ChronoUnit.HOURS.between(start, end);
    }

    public static long minutesBetween(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return 0;
        }
        return ChronoUnit.MINUTES.between(start, end);
    }

    public static long secondsBetween(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return 0;
        }
        return ChronoUnit.SECONDS.between(start, end);
    }

    public static boolean isBefore(LocalDate date1, LocalDate date2) {
        if (date1 == null || date2 == null) {
            return false;
        }
        return date1.isBefore(date2);
    }

    public static boolean isAfter(LocalDate date1, LocalDate date2) {
        if (date1 == null || date2 == null) {
            return false;
        }
        return date1.isAfter(date2);
    }

    public static LocalDate startOfMonth(LocalDate date) {
        return date != null ? date.withDayOfMonth(1) : null;
    }

    public static LocalDate endOfMonth(LocalDate date) {
        return date != null ? date.withDayOfMonth(date.lengthOfMonth()) : null;
    }

    public static LocalDateTime startOfDay(LocalDate date) {
        return date != null ? date.atStartOfDay() : null;
    }

    public static LocalDateTime endOfDay(LocalDate date) {
        return date != null ? date.atTime(LocalTime.MAX) : null;
    }
}
