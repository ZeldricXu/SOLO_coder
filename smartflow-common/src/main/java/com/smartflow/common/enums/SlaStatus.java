package com.smartflow.common.enums;

import lombok.Getter;

@Getter
public enum SlaStatus {

    NORMAL(0, "正常"),
    WARNING(1, "预警"),
    OVERDUE(2, "已超时");

    private final Integer code;
    private final String desc;

    SlaStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
