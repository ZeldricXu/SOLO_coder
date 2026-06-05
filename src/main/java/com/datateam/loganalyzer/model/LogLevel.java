package com.datateam.loganalyzer.model;

public enum LogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    FATAL,
    UNKNOWN;

    public static LogLevel fromString(String level) {
        if (level == null) return UNKNOWN;
        try {
            return LogLevel.valueOf(level.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            String upper = level.toUpperCase().trim();
            if (upper.contains("WARNING")) return WARN;
            if (upper.contains("ERR")) return ERROR;
            if (upper.contains("CRIT") || upper.contains("SEVERE")) return FATAL;
            return UNKNOWN;
        }
    }

    public boolean isErrorOrWorse() {
        return this == ERROR || this == FATAL;
    }
}
