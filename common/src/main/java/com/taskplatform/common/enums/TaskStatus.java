package com.taskplatform.common.enums;

public enum TaskStatus {
    PENDING("pending"),
    QUEUED("queued"),
    SCHEDULED("scheduled"),
    RUNNING("running"),
    PAUSED("paused"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    TIMEOUT("timeout");

    private final String value;

    TaskStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static TaskStatus fromValue(String value) {
        for (TaskStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown task status: " + value);
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == TIMEOUT;
    }
}
