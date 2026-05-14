package com.supplychain.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum SupplierStatus {
    PENDING("pending", "待审核"),
    QUALIFIED("qualified", "合格"),
    DISQUALIFIED("disqualified", "不合格"),
    SUSPENDED("suspended", "已停用");

    private final String code;
    private final String desc;
}
