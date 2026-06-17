package com.enterprise.risk.common.event;

public enum EventBusinessLine {
    PAYMENT("payment", "支付"),
    LOGIN("login", "登录"),
    MARKETING("marketing", "营销"),
    TRANSACTION("transaction", "交易");

    private final String code;
    private final String description;

    EventBusinessLine(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static EventBusinessLine fromCode(String code) {
        for (EventBusinessLine line : values()) {
            if (line.code.equalsIgnoreCase(code)) {
                return line;
            }
        }
        throw new IllegalArgumentException("Unknown business line: " + code);
    }
}
