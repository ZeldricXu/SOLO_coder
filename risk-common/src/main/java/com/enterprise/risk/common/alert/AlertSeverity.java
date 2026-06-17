package com.enterprise.risk.common.alert;

public enum AlertSeverity {
    INFO(1, "info"),
    WARNING(2, "warning"),
    MEDIUM(3, "medium"),
    HIGH(4, "high"),
    CRITICAL(5, "critical");

    private final int level;
    private final String code;

    AlertSeverity(int level, String code) {
        this.level = level;
        this.code = code;
    }

    public int getLevel() {
        return level;
    }

    public String getCode() {
        return code;
    }

    public boolean isHigherThan(AlertSeverity other) {
        return this.level > other.level;
    }

    public boolean isLowerThan(AlertSeverity other) {
        return this.level < other.level;
    }

    public static AlertSeverity fromCode(String code) {
        for (AlertSeverity severity : values()) {
            if (severity.code.equalsIgnoreCase(code)) {
                return severity;
            }
        }
        throw new IllegalArgumentException("Unknown alert severity: " + code);
    }
}
