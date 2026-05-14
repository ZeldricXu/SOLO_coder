package com.homeservice.enums;

public enum SettlementStatus {
    PENDING("pending"),
    PAID("paid"),
    FAILED("failed");

    private final String value;

    SettlementStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
