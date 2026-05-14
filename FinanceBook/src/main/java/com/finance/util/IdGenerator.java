package com.finance.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public class IdGenerator {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public static String generateId(String prefix) {
        String timestamp = LocalDateTime.now().format(DATE_FORMATTER);
        String uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        return prefix + "_" + timestamp + uuid;
    }

    public static String generateAccountId() {
        return generateId("account");
    }

    public static String generateRecordId() {
        return generateId("record");
    }

    public static String generateCategoryId() {
        return generateId("category");
    }

    public static String generateBudgetId() {
        return generateId("budget");
    }

    public static String generateReportId() {
        return generateId("report");
    }

    public static String generateReminderId() {
        return generateId("reminder");
    }

    public static String generateStatId() {
        return generateId("stat");
    }

    public static String generateTypeId() {
        return generateId("type");
    }
}
