package com.homeservice.enums;

public enum StaffStatus {
    AVAILABLE("available"),
    BOOKED("booked"),
    UNAVAILABLE("unavailable");

    private final String value;

    StaffStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
