package com.logmanager.common.enums;

import lombok.Getter;

@Getter
public enum NotificationPriority {
    LOW(1, "低"),
    MEDIUM(2, "中"),
    HIGH(3, "高"),
    CRITICAL(4, "紧急");

    private final int level;
    private final String description;

    NotificationPriority(int level, String description) {
        this.level = level;
        this.description = description;
    }

    public boolean isHigherOrEqual(NotificationPriority other) {
        return this.level >= other.level;
    }
}
