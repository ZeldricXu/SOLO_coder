package com.formflow.enums;

public enum ApprovalResult {
    APPROVED("通过"),
    REJECTED("拒绝"),
    TRANSFER("转交"),
    ADD_SIGNER("加签"),
    DELEGATE("委托");

    private final String description;

    ApprovalResult(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
