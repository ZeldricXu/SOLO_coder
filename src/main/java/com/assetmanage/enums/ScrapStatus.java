package com.assetmanage.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ScrapStatus {

    PENDING("pending", "待审批"),
    APPROVED("approved", "已批准"),
    REJECTED("rejected", "已拒绝");

    private final String code;
    private final String description;
}
