package com.stockmgmt.enums;

import lombok.Getter;

@Getter
public enum LockStatus {

    LOCKED("locked", "已锁定"),
    RELEASED("released", "已释放"),
    EXPIRED("expired", "已过期");

    private final String code;
    private final String desc;

    LockStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static LockStatus fromCode(String code) {
        for (LockStatus status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        return null;
    }
}
