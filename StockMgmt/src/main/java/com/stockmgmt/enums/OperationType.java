package com.stockmgmt.enums;

import lombok.Getter;

@Getter
public enum OperationType {

    INBOUND("inbound", "入库"),
    OUTBOUND("outbound", "出库"),
    TRANSFER("transfer", "调拨"),
    ADJUST("adjust", "调整"),
    LOCK("lock", "锁定"),
    UNLOCK("unlock", "解锁");

    private final String code;
    private final String desc;

    OperationType(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static OperationType fromCode(String code) {
        for (OperationType type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }
}
