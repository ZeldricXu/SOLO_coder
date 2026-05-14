package com.invoice.mgmt.common.enums;

import lombok.Getter;

@Getter
public enum VerifyResultEnum {
    VALID("valid", "有效"),
    INVALID("invalid", "无效"),
    TIMEOUT("timeout", "超时");

    private final String code;
    private final String desc;

    VerifyResultEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
