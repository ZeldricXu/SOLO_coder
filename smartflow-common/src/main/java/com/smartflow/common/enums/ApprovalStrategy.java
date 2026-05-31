package com.smartflow.common.enums;

import lombok.Getter;

@Getter
public enum ApprovalStrategy {

    ANY(1, "或签"),
    ALL(2, "会签");

    private final Integer code;
    private final String desc;

    ApprovalStrategy(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
