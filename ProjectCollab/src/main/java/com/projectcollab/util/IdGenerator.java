package com.projectcollab.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

public class IdGenerator {

    private static final AtomicInteger projectCounter = new AtomicInteger(1);
    private static final AtomicInteger taskCounter = new AtomicInteger(1);
    private static final AtomicInteger memberCounter = new AtomicInteger(1);
    private static final AtomicInteger progressCounter = new AtomicInteger(1);
    private static final AtomicInteger docCounter = new AtomicInteger(1);
    private static final AtomicInteger reminderCounter = new AtomicInteger(1);
    private static final AtomicInteger stageCounter = new AtomicInteger(1);
    private static final AtomicInteger statCounter = new AtomicInteger(1);
    private static final AtomicInteger historyCounter = new AtomicInteger(1);

    private static String getTimestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
    }

    public static String generateProjectId() {
        return "project_" + String.format("%03d", projectCounter.getAndIncrement());
    }

    public static String generateTaskId() {
        return "task_" + String.format("%03d", taskCounter.getAndIncrement());
    }

    public static String generateMemberId() {
        return "member_" + String.format("%03d", memberCounter.getAndIncrement());
    }

    public static String generateProgressId() {
        return "progress_" + String.format("%03d", progressCounter.getAndIncrement());
    }

    public static String generateDocId() {
        return "doc_" + String.format("%03d", docCounter.getAndIncrement());
    }

    public static String generateReminderId() {
        return "reminder_" + String.format("%03d", reminderCounter.getAndIncrement());
    }

    public static String generateStageId() {
        return "stage_" + String.format("%03d", stageCounter.getAndIncrement());
    }

    public static String generateStatId() {
        return "stat_" + String.format("%03d", statCounter.getAndIncrement());
    }

    public static String generateHistoryId() {
        return "history_" + String.format("%03d", historyCounter.getAndIncrement());
    }

    public static String getCurrentMonth() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }
}
