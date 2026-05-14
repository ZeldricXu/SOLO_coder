package com.stockmgmt.enums;

import lombok.Getter;

@Getter
public enum DiffHandleStatus {

    PENDING("pending", "待处理"),
    APPROVED("approved", "已批准"),
    REJECTED("rejected", "已拒绝"),
    PROCESSED("processed", "已处理");

    private final String code;
    private final String desc;

    DiffHandleStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static DiffHandleStatus fromCode(String code) {
        for (DiffHandleStatus status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        return null;
    }
}
