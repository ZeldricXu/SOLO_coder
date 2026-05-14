package com.restaurant.mgmt.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

public class IdGenerator {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final AtomicInteger COUNTER = new AtomicInteger(0);

    public static String generate(String prefix) {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        int sequence = COUNTER.incrementAndGet() % 1000;
        return String.format("%s_%s%03d", prefix, timestamp, sequence);
    }

    public static String generateDishId() {
        return generate("dish");
    }

    public static String generateOrderId() {
        return generate("order");
    }

    public static String generateTableId() {
        return generate("table");
    }

    public static String generateStockId() {
        return generate("stock");
    }

    public static String generateWarningId() {
        return generate("warning");
    }

    public static String generateStatId() {
        return generate("stat");
    }

    public static String generateIngredientId() {
        return generate("ingredient");
    }

    public static String generateEmployeeId() {
        return generate("emp");
    }

    public static String generateReviewId() {
        return generate("review");
    }

    public static String generatePromotionId() {
        return generate("promo");
    }

    public static String generateHistoryId() {
        return generate("history");
    }

    public static String generateTableTaskId() {
        return generate("table_task");
    }
}
