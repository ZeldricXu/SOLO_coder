package com.formflow.enums;

public enum FormStatus {
    DRAFT("草稿"),
    PENDING_APPROVAL("待审批"),
    APPROVED("已通过"),
    REJECTED("已拒绝"),
    CANCELED("已取消");

    private final String description;

    FormStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
