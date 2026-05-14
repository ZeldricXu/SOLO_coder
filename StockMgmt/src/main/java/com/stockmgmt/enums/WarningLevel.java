package com.stockmgmt.enums;

import lombok.Getter;

@Getter
public enum WarningLevel {

    LOW("low", "低"),
    MEDIUM("medium", "中"),
    HIGH("high", "高");

    private final String code;
    private final String desc;

    WarningLevel(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static WarningLevel fromCode(String code) {
        for (WarningLevel level : values()) {
            if (level.getCode().equals(code)) {
                return level;
            }
        }
        return null;
    }
}
