package com.stockmgmt.enums;

import lombok.Getter;

@Getter
public enum WarningType {

    LOW_STOCK("low_stock", "库存不足"),
    OVERSTOCK("overstock", "库存积压");

    private final String code;
    private final String desc;

    WarningType(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static WarningType fromCode(String code) {
        for (WarningType type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }
}
