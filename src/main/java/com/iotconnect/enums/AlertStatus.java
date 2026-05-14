package com.iotconnect.enums;

public enum AlertStatus {
    TRIGGERED("triggered"),
    RESOLVED("resolved"),
    ACKNOWLEDGED("acknowledged");

    private final String value;

    AlertStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static AlertStatus fromValue(String value) {
        for (AlertStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        return TRIGGERED;
    }
}
