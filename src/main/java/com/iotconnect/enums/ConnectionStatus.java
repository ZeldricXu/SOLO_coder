package com.iotconnect.enums;

public enum ConnectionStatus {
    ONLINE("online"),
    OFFLINE("offline"),
    CONNECTING("connecting"),
    DISCONNECTED("disconnected");

    private final String value;

    ConnectionStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static ConnectionStatus fromValue(String value) {
        for (ConnectionStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        return OFFLINE;
    }
}
