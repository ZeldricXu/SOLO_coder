package com.smartflow.common.enums;

import lombok.Getter;

@Getter
public enum TicketStatus {

    PENDING(0, "待处理"),
    ASSIGNED(1, "已分配"),
    PROCESSING(2, "处理中"),
    RESOLVED(3, "已解决"),
    CLOSED(4, "已关闭"),
    ESCALATED(5, "已升级");

    private final Integer code;
    private final String desc;

    TicketStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
