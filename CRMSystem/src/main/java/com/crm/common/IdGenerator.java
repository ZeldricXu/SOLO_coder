package com.crm.common;

import java.util.concurrent.atomic.AtomicLong;

public class IdGenerator {
    private static final AtomicLong customerCounter = new AtomicLong(1);
    private static final AtomicLong followCounter = new AtomicLong(1);
    private static final AtomicLong opportunityCounter = new AtomicLong(1);
    private static final AtomicLong categoryCounter = new AtomicLong(1);
    private static final AtomicLong tagCounter = new AtomicLong(1);
    private static final AtomicLong reminderCounter = new AtomicLong(1);
    private static final AtomicLong statCounter = new AtomicLong(1);

    public static String generateCustomerId() {
        return String.format("customer_%03d", customerCounter.getAndIncrement());
    }

    public static String generateFollowId() {
        return String.format("follow_%03d", followCounter.getAndIncrement());
    }

    public static String generateOpportunityId() {
        return String.format("opp_%03d", opportunityCounter.getAndIncrement());
    }

    public static String generateCategoryId() {
        return String.format("category_%03d", categoryCounter.getAndIncrement());
    }

    public static String generateTagId() {
        return String.format("tag_%03d", tagCounter.getAndIncrement());
    }

    public static String generateReminderId() {
        return String.format("reminder_%03d", reminderCounter.getAndIncrement());
    }

    public static String generateStatId() {
        return String.format("stat_%03d", statCounter.getAndIncrement());
    }
}
