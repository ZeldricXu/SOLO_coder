package com.formflow.enums;

public enum ProcessInstanceStatus {
    RUNNING("运行中"),
    COMPLETED("已完成"),
    REJECTED("已拒绝"),
    CANCELED("已取消"),
    SUSPENDED("已暂停");

    private final String description;

    ProcessInstanceStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
