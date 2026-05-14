package com.homeservice.enums;

public enum BookingStatus {
    CONFIRMED("confirmed"),
    EXECUTING("executing"),
    COMPLETED("completed"),
    CANCELLED("cancelled");

    private final String value;

    BookingStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
