package com.stockmgmt.enums;

import lombok.Getter;

@Getter
public enum CheckType {

    FULL("full", "全盘"),
    PARTIAL("partial", "抽盘"),
    CYCLE("cycle", "循环盘点");

    private final String code;
    private final String desc;

    CheckType(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static CheckType fromCode(String code) {
        for (CheckType type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }
}
