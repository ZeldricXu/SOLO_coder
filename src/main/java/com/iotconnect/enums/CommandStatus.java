package com.iotconnect.enums;

public enum CommandStatus {
    PENDING("pending"),
    EXECUTING("executing"),
    SUCCESS("success"),
    FAILED("failed"),
    TIMEOUT("timeout");

    private final String value;

    CommandStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static CommandStatus fromValue(String value) {
        for (CommandStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        return PENDING;
    }
}
