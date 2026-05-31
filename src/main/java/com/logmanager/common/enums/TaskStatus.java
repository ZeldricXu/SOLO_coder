package com.logmanager.common.enums;

import lombok.Getter;

@Getter
public enum TaskStatus {
    PENDING("pending", "待执行"),
    RUNNING("running", "执行中"),
    COMPLETED("completed", "已完成"),
    FAILED("failed", "失败"),
    CANCELLED("cancelled", "已取消"),
    TIMEOUT("timeout", "超时");

    private final String code;
    private final String description;

    TaskStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == TIMEOUT;
    }
}
