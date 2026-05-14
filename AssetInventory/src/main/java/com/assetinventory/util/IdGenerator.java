package com.assetinventory.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

public class IdGenerator {

    private static final AtomicInteger assetCounter = new AtomicInteger(1);
    private static final AtomicInteger taskCounter = new AtomicInteger(1);
    private static final AtomicInteger countCounter = new AtomicInteger(1);
    private static final AtomicInteger diffCounter = new AtomicInteger(1);
    private static final AtomicInteger personCounter = new AtomicInteger(1);
    private static final AtomicInteger planCounter = new AtomicInteger(1);
    private static final AtomicInteger statCounter = new AtomicInteger(1);
    private static final AtomicInteger categoryCounter = new AtomicInteger(1);

    private IdGenerator() {
    }

    public static String generateAssetId() {
        return "asset_" + String.format("%03d", assetCounter.getAndIncrement());
    }

    public static String generateTaskId() {
        return "task_" + String.format("%03d", taskCounter.getAndIncrement());
    }

    public static String generateCountId() {
        return "count_" + String.format("%03d", countCounter.getAndIncrement());
    }

    public static String generateDiffId() {
        return "diff_" + String.format("%03d", diffCounter.getAndIncrement());
    }

    public static String generatePersonId() {
        return "person_" + String.format("%03d", personCounter.getAndIncrement());
    }

    public static String generatePlanId() {
        return "plan_" + String.format("%03d", planCounter.getAndIncrement());
    }

    public static String generateStatId() {
        return "stat_" + String.format("%03d", statCounter.getAndIncrement());
    }

    public static String generateCategoryId() {
        return "category_" + String.format("%03d", categoryCounter.getAndIncrement());
    }

    public static String getCurrentMonth() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    public static Instant now() {
        return Instant.now();
    }
}
