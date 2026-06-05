package com.datateam.loganalyzer.model;

public enum AlertSeverity {
    INFO(1),
    WARNING(2),
    ERROR(3),
    CRITICAL(4);

    private final int level;

    AlertSeverity(int level) {
        this.level = level;
    }

    public int getLevel() {
        return level;
    }

    public AlertSeverity escalate() {
        switch (this) {
            case INFO: return WARNING;
            case WARNING: return ERROR;
            case ERROR: return CRITICAL;
            default: return CRITICAL;
        }
    }

    public boolean isHigherThan(AlertSeverity other) {
        return this.level > other.level;
    }
}
