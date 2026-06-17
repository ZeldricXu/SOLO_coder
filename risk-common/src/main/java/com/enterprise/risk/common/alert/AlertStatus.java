package com.enterprise.risk.common.alert;

public enum AlertStatus {
    OPEN("open"),
    SUPPRESSED("suppressed"),
    ESCALATED("escalated"),
    ACKNOWLEDGED("acknowledged"),
    IN_PROGRESS("in_progress"),
    RESOLVED("resolved"),
    CLOSED("closed"),
    FALSE_POSITIVE("false_positive");

    private final String code;

    AlertStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
