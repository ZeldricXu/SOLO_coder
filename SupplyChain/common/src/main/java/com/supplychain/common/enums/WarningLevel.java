package com.supplychain.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum WarningLevel {
    LOW("low", "低"),
    MEDIUM("medium", "中"),
    HIGH("high", "高"),
    CRITICAL("critical", "严重");

    private final String code;
    private final String desc;
}
