package com.homeservice.enums;

public enum CustomerLevel {
    PLATINUM("platinum", "白金客户"),
    GOLD("gold", "黄金客户"),
    SILVER("silver", "白银客户"),
    BRONZE("bronze", "青铜客户"),
    NEW("new", "新客户");

    private final String code;
    private final String displayName;

    CustomerLevel(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static CustomerLevel fromCode(String code) {
        if (code == null) return NEW;
        for (CustomerLevel level : values()) {
            if (level.getCode().equalsIgnoreCase(code)) {
                return level;
            }
        }
        return NEW;
    }
}
