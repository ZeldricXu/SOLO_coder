package com.smartflow.common.enums;

import lombok.Getter;

@Getter
public enum ApprovalStatus {

    PENDING(0, "待审批"),
    APPROVED(1, "已通过"),
    REJECTED(2, "已拒绝"),
    ESCALATED(3, "已升级"),
    CANCELED(4, "已取消");

    private final Integer code;
    private final String desc;

    ApprovalStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
