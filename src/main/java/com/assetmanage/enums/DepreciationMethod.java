package com.assetmanage.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum DepreciationMethod {

    STRAIGHT_LINE("straight_line", "直线法"),
    ACCELERATED("accelerated", "加速折旧法"),
    DOUBLE_DECLINING("double_declining", "双倍余额递减法");

    private final String code;
    private final String description;
}
