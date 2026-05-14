package com.invoice.mgmt.common.enums;

import lombok.Getter;

@Getter
public enum InvoiceStatusEnum {
    PENDING("pending", "待开具"),
    ISSUED("issued", "已开具"),
    VERIFIED("verified", "已验证"),
    REIMBURSE_PENDING("reimburse_pending", "报销审核中"),
    REIMBURSED("reimbursed", "已报销"),
    CANCELLED("cancelled", "已作废"),
    INVALID("invalid", "已无效");

    private final String code;
    private final String desc;

    InvoiceStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static InvoiceStatusEnum of(String code) {
        for (InvoiceStatusEnum e : values()) {
            if (e.getCode().equals(code)) {
                return e;
            }
        }
        return null;
    }
}
