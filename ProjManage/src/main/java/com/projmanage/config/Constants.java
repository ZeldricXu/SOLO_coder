package com.projmanage.config;

public class Constants {

    public static final String PROJECT_STATUS_PENDING = "pending";
    public static final String PROJECT_STATUS_IN_PROGRESS = "in_progress";
    public static final String PROJECT_STATUS_COMPLETED = "completed";

    public static final String TASK_STATUS_PENDING = "pending";
    public static final String TASK_STATUS_IN_PROGRESS = "in_progress";
    public static final String TASK_STATUS_COMPLETED = "completed";

    public static final String TASK_PRIORITY_LOW = "low";
    public static final String TASK_PRIORITY_MEDIUM = "medium";
    public static final String TASK_PRIORITY_HIGH = "high";

    public static final String MILESTONE_STATUS_PENDING = "pending";
    public static final String MILESTONE_STATUS_IN_PROGRESS = "in_progress";
    public static final String MILESTONE_STATUS_COMPLETED = "completed";

    public static final String RISK_STATUS_IDENTIFIED = "identified";
    public static final String RISK_STATUS_MONITORING = "monitoring";
    public static final String RISK_STATUS_RESOLVED = "resolved";

    public static final String RISK_LEVEL_LOW = "low";
    public static final String RISK_LEVEL_MEDIUM = "medium";
    public static final String RISK_LEVEL_HIGH = "high";

    public static final String RISK_TYPE_SCHEDULE_DELAY = "schedule_delay";
    public static final String RISK_TYPE_RESOURCE_SHORTAGE = "resource_shortage";
    public static final String RISK_TYPE_TECHNICAL_ISSUE = "technical_issue";

    public static final String DISCUSSION_TYPE_COMMENT = "comment";
    public static final String DISCUSSION_TYPE_NOTE = "note";
    public static final String DISCUSSION_TYPE_DECISION = "decision";

    public static final String NOTIFICATION_TYPE_TASK_ASSIGNED = "task_assigned";
    public static final String NOTIFICATION_TYPE_TASK_UPDATED = "task_updated";
    public static final String NOTIFICATION_TYPE_MILESTONE_REMINDER = "milestone_reminder";
    public static final String NOTIFICATION_TYPE_RISK_ALERT = "risk_alert";

    public static final String REPORT_TYPE_DAILY = "daily";
    public static final String REPORT_TYPE_WEEKLY = "weekly";
    public static final String REPORT_TYPE_MONTHLY = "monthly";

    public static final String ACTIVITY_LEVEL_HIGH = "high";
    public static final String ACTIVITY_LEVEL_MEDIUM = "medium";
    public static final String ACTIVITY_LEVEL_LOW = "low";
    public static final String ACTIVITY_LEVEL_INACTIVE = "inactive";

    public static final Integer STAT_FREQUENCY_HIGH_MINUTES = 5;
    public static final Integer STAT_FREQUENCY_MEDIUM_MINUTES = 15;
    public static final Integer STAT_FREQUENCY_LOW_MINUTES = 60;
    public static final Integer STAT_FREQUENCY_INACTIVE_MINUTES = 1440;

    public static final Double SORT_PRIORITY_WEIGHT = 0.5;
    public static final Double SORT_URGENCY_WEIGHT = 0.35;
    public static final Double SORT_WORKLOAD_WEIGHT = 0.15;

    public static final Integer SORT_MAX_SCORE = 100;

    public static final String REDIS_RISK_QUEUE_KEY = "risk:detection:queue";
    public static final String REDIS_RISK_PROCESSING_KEY = "risk:detection:processing";
    public static final String REDIS_RISK_FAILED_KEY = "risk:detection:failed";

    public static final Integer DEFAULT_REMINDER_DAYS_BEFORE = 3;
    public static final Integer DEFAULT_MAX_REMINDER_COUNT = 5;
    public static final Integer DEFAULT_REMINDER_INTERVAL_HOURS = 24;

    public static final Integer HIGH_ACTIVITY_THRESHOLD = 20;
    public static final Integer MEDIUM_ACTIVITY_THRESHOLD = 10;
    public static final Integer LOW_ACTIVITY_THRESHOLD = 3;

    private Constants() {
    }
}
