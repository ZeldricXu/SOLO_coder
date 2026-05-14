package com.fooddelivery.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

public class IdGenerator {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final AtomicInteger RESTAURANT_COUNTER = new AtomicInteger(1);
    private static final AtomicInteger ORDER_COUNTER = new AtomicInteger(1);
    private static final AtomicInteger DELIVERY_COUNTER = new AtomicInteger(1);
    private static final AtomicInteger RIDER_COUNTER = new AtomicInteger(1);
    private static final AtomicInteger TRACK_COUNTER = new AtomicInteger(1);
    private static final AtomicInteger NOTIFY_COUNTER = new AtomicInteger(1);
    private static final AtomicInteger STAT_COUNTER = new AtomicInteger(1);
    private static final AtomicInteger REVIEW_COUNTER = new AtomicInteger(1);
    private static final AtomicInteger REGION_COUNTER = new AtomicInteger(1);
    private static final AtomicInteger DISH_COUNTER = new AtomicInteger(1);
    private static final AtomicInteger HISTORY_COUNTER = new AtomicInteger(1);

    public static String generateRestaurantId() {
        return "restaurant_" + String.format("%03d", RESTAURANT_COUNTER.getAndIncrement());
    }

    public static String generateOrderId() {
        return "order_" + String.format("%03d", ORDER_COUNTER.getAndIncrement());
    }

    public static String generateDeliveryId() {
        return "delivery_" + String.format("%03d", DELIVERY_COUNTER.getAndIncrement());
    }

    public static String generateRiderId() {
        return "rider_" + String.format("%03d", RIDER_COUNTER.getAndIncrement());
    }

    public static String generateTrackId() {
        return "track_" + String.format("%03d", TRACK_COUNTER.getAndIncrement());
    }

    public static String generateNotifyId() {
        return "notify_" + String.format("%03d", NOTIFY_COUNTER.getAndIncrement());
    }

    public static String generateStatId() {
        return "stat_" + String.format("%03d", STAT_COUNTER.getAndIncrement());
    }

    public static String generateReviewId() {
        return "review_" + String.format("%03d", REVIEW_COUNTER.getAndIncrement());
    }

    public static String generateRegionId() {
        return "region_" + String.format("%03d", REGION_COUNTER.getAndIncrement());
    }

    public static String generateDishId() {
        return "dish_" + String.format("%03d", DISH_COUNTER.getAndIncrement());
    }

    public static String generateHistoryId() {
        return "history_" + String.format("%03d", HISTORY_COUNTER.getAndIncrement());
    }

    public static String generateTimestamp() {
        return LocalDateTime.now().format(FORMATTER);
    }
}
