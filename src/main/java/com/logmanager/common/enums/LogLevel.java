package com.logmanager.common.enums;

import lombok.Getter;

@Getter
public enum LogLevel {
    TRACE(0, "TRACE"),
    DEBUG(1, "DEBUG"),
    INFO(2, "INFO"),
    WARN(3, "WARN"),
    ERROR(4, "ERROR"),
    FATAL(5, "FATAL"),
    OFF(6, "OFF");

    private final int order;
    private final String displayName;

    LogLevel(int order, String displayName) {
        this.order = order;
        this.displayName = displayName;
    }

    public boolean isHigherOrEqual(LogLevel other) {
        return this.order >= other.order;
    }

    public static LogLevel fromString(String level) {
        for (LogLevel logLevel : values()) {
            if (logLevel.displayName.equalsIgnoreCase(level)) {
                return logLevel;
            }
        }
        throw new IllegalArgumentException("Unknown log level: " + level);
    }
}
