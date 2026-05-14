package com.invoice.mgmt.common.enums;

import lombok.Getter;

@Getter
public enum ReimburseStatusEnum {
    PENDING("pending", "待审核"),
    APPROVED("approved", "已通过"),
    REJECTED("rejected", "已拒绝");

    private final String code;
    private final String desc;

    ReimburseStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
