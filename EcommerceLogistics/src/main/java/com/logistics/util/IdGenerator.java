package com.logistics.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class IdGenerator {

    private static final AtomicInteger logisticsCounter = new AtomicInteger(0);
    private static final AtomicInteger taskCounter = new AtomicInteger(0);
    private static final AtomicInteger trackCounter = new AtomicInteger(0);
    private static final AtomicInteger notifyCounter = new AtomicInteger(0);
    private static final AtomicInteger statCounter = new AtomicInteger(0);
    private static final AtomicInteger historyCounter = new AtomicInteger(0);

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public static String generateLogisticsId() {
        return "logistics_" + getTimestamp() + String.format("%03d", logisticsCounter.incrementAndGet() % 1000);
    }

    public static String generateLogisticsNumber() {
        return "SF" + getTimestamp() + String.format("%04d", logisticsCounter.incrementAndGet() % 10000);
    }

    public static String generateTaskId() {
        return "task_" + getTimestamp() + String.format("%03d", taskCounter.incrementAndGet() % 1000);
    }

    public static String generateTrackId() {
        return "track_" + getTimestamp() + String.format("%03d", trackCounter.incrementAndGet() % 1000);
    }

    public static String generateNotifyId() {
        return "notify_" + getTimestamp() + String.format("%03d", notifyCounter.incrementAndGet() % 1000);
    }

    public static String generateStatId() {
        return "stat_" + getTimestamp() + String.format("%03d", statCounter.incrementAndGet() % 1000);
    }

    public static String generateHistoryId() {
        return "history_" + getTimestamp() + String.format("%03d", historyCounter.incrementAndGet() % 1000);
    }

    public static String generateStationId() {
        return "station_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateCourierId() {
        return "courier_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String getTimestamp() {
        return LocalDateTime.now().format(DATE_FORMATTER);
    }

    public static String getCurrentMonth() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }
}
