package com.invoice.mgmt.common.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class DateTimeUtil {
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter FULL_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static String getCurrentMonth() {
        return LocalDate.now().format(MONTH_FORMAT);
    }

    public static String getMonth(Instant instant) {
        return instant.atZone(ZoneId.systemDefault()).toLocalDate().format(MONTH_FORMAT);
    }

    public static Instant now() {
        return Instant.now();
    }

    public static String formatDate(Instant instant) {
        return instant.atZone(ZoneId.systemDefault()).toLocalDate().format(DATE_FORMAT);
    }

    public static String formatFull(Instant instant) {
        return instant.atZone(ZoneId.systemDefault()).format(FULL_FORMAT);
    }
}
