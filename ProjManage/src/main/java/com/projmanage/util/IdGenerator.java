package com.projmanage.util;

import java.util.concurrent.atomic.AtomicInteger;

public class IdGenerator {

    private static final AtomicInteger projectCounter = new AtomicInteger(1);
    private static final AtomicInteger taskCounter = new AtomicInteger(1);
    private static final AtomicInteger discussionCounter = new AtomicInteger(1);
    private static final AtomicInteger progressCounter = new AtomicInteger(1);
    private static final AtomicInteger milestoneCounter = new AtomicInteger(1);
    private static final AtomicInteger riskCounter = new AtomicInteger(1);
    private static final AtomicInteger statisticCounter = new AtomicInteger(1);
    private static final AtomicInteger documentCounter = new AtomicInteger(1);
    private static final AtomicInteger notificationCounter = new AtomicInteger(1);
    private static final AtomicInteger reportCounter = new AtomicInteger(1);

    private IdGenerator() {
    }

    public static String generateProjectId() {
        return "project_" + String.format("%03d", projectCounter.getAndIncrement());
    }

    public static String generateTaskId() {
        return "task_" + String.format("%03d", taskCounter.getAndIncrement());
    }

    public static String generateDiscussionId() {
        return "discuss_" + String.format("%03d", discussionCounter.getAndIncrement());
    }

    public static String generateProgressId() {
        return "progress_" + String.format("%03d", progressCounter.getAndIncrement());
    }

    public static String generateMilestoneId() {
        return "milestone_" + String.format("%03d", milestoneCounter.getAndIncrement());
    }

    public static String generateRiskId() {
        return "risk_" + String.format("%03d", riskCounter.getAndIncrement());
    }

    public static String generateStatisticId() {
        return "stat_" + String.format("%03d", statisticCounter.getAndIncrement());
    }

    public static String generateDocumentId() {
        return "doc_" + String.format("%03d", documentCounter.getAndIncrement());
    }

    public static String generateNotificationId() {
        return "notif_" + String.format("%03d", notificationCounter.getAndIncrement());
    }

    public static String generateReportId() {
        return "report_" + String.format("%03d", reportCounter.getAndIncrement());
    }
}
