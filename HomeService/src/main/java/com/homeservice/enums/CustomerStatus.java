package com.homeservice.enums;

public enum CustomerStatus {
    ACTIVE("active"),
    FROZEN("frozen");

    private final String value;

    CustomerStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
