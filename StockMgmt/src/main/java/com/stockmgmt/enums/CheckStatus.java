package com.stockmgmt.enums;

import lombok.Getter;

@Getter
public enum CheckStatus {

    PENDING("pending", "待执行"),
    PROCESSING("processing", "执行中"),
    COMPLETED("completed", "已完成"),
    CANCELLED("cancelled", "已取消");

    private final String code;
    private final String desc;

    CheckStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static CheckStatus fromCode(String code) {
        for (CheckStatus status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        return null;
    }
}
