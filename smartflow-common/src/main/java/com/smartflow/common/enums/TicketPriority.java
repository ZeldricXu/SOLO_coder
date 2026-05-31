package com.smartflow.common.enums;

import lombok.Getter;

@Getter
public enum TicketPriority {

    LOW(0, "低"),
    MEDIUM(1, "中"),
    HIGH(2, "高"),
    URGENT(3, "紧急");

    private final Integer code;
    private final String desc;

    TicketPriority(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
