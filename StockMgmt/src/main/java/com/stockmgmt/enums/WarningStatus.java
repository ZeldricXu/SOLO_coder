package com.stockmgmt.enums;

import lombok.Getter;

@Getter
public enum WarningStatus {

    ACTIVE("active", "活动中"),
    HANDLED("handled", "已处理");

    private final String code;
    private final String desc;

    WarningStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static WarningStatus fromCode(String code) {
        for (WarningStatus status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        return null;
    }
}
