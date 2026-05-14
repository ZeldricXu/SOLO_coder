package com.assetmanage.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum UsageStatus {

    ACTIVE("active", "使用中"),
    RETURNED("returned", "已归还"),
    CANCELLED("cancelled", "已取消");

    private final String code;
    private final String description;
}
