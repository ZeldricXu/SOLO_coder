package com.invoice.mgmt.common.enums;

import lombok.Getter;

@Getter
public enum ActionTypeEnum {
    ISSUE("issue", "开具"),
    VERIFY("verify", "验证"),
    REIMBURSE_APPLY("reimburse_apply", "报销申请"),
    REIMBURSE_APPROVE("reimburse_approve", "报销通过"),
    REIMBURSE_REJECT("reimburse_reject", "报销拒绝"),
    CANCEL("cancel", "作废"),
    ARCHIVE("archive", "归档"),
    STATUS_CHANGE("status_change", "状态变更");

    private final String code;
    private final String desc;

    ActionTypeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
