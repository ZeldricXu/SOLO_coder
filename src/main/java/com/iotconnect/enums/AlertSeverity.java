package com.iotconnect.enums;

public enum AlertSeverity {
    CRITICAL("critical"),
    HIGH("high"),
    MEDIUM("medium"),
    LOW("low");

    private final String value;

    AlertSeverity(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static AlertSeverity fromValue(String value) {
        for (AlertSeverity severity : values()) {
            if (severity.value.equalsIgnoreCase(value)) {
                return severity;
            }
        }
        return LOW;
    }
}
