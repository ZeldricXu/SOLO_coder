package com.fitnesscenter.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class IdGenerator {

    private static final AtomicInteger memberCounter = new AtomicInteger(0);
    private static final AtomicInteger courseCounter = new AtomicInteger(0);
    private static final AtomicInteger bookingCounter = new AtomicInteger(0);
    private static final AtomicInteger trainingCounter = new AtomicInteger(0);
    private static final AtomicInteger planCounter = new AtomicInteger(0);
    private static final AtomicInteger coachCounter = new AtomicInteger(0);
    private static final AtomicInteger statCounter = new AtomicInteger(0);
    private static final AtomicInteger gymCounter = new AtomicInteger(0);
    private static final AtomicInteger equipmentCounter = new AtomicInteger(0);
    private static final AtomicInteger historyCounter = new AtomicInteger(0);

    private static String getDatePrefix() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
    }

    public static String generateMemberId() {
        return "member_" + getDatePrefix() + String.format("%04d", memberCounter.incrementAndGet());
    }

    public static String generateCourseId() {
        return "course_" + getDatePrefix() + String.format("%04d", courseCounter.incrementAndGet());
    }

    public static String generateBookingId() {
        return "booking_" + getDatePrefix() + String.format("%04d", bookingCounter.incrementAndGet());
    }

    public static String generateTrainingId() {
        return "training_" + getDatePrefix() + String.format("%04d", trainingCounter.incrementAndGet());
    }

    public static String generatePlanId() {
        return "plan_" + getDatePrefix() + String.format("%04d", planCounter.incrementAndGet());
    }

    public static String generateCoachId() {
        return "coach_" + getDatePrefix() + String.format("%04d", coachCounter.incrementAndGet());
    }

    public static String generateStatId() {
        return "stat_" + getDatePrefix() + String.format("%04d", statCounter.incrementAndGet());
    }

    public static String generateGymId() {
        return "gym_" + getDatePrefix() + String.format("%04d", gymCounter.incrementAndGet());
    }

    public static String generateEquipmentId() {
        return "equipment_" + getDatePrefix() + String.format("%04d", equipmentCounter.incrementAndGet());
    }

    public static String generateHistoryId() {
        return "history_" + getDatePrefix() + String.format("%04d", historyCounter.incrementAndGet());
    }

    public static String generateUUID() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
