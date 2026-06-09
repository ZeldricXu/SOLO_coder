package com.loganalytics.common.model;

public enum LogLevel {
    TRACE, DEBUG, INFO, WARN, ERROR, FATAL, UNKNOWN;

    public static LogLevel fromString(String value) {
        if (value == null) return UNKNOWN;
        try {
            return LogLevel.valueOf(value.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }

    public boolean isMoreSevereThan(LogLevel other) {
        return this.ordinal() > other.ordinal();
    }
}
