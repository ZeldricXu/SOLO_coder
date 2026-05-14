package com.invoice.mgmt.common.enums;

import lombok.Getter;

@Getter
public enum VerifyTypeEnum {
    ONLINE("online", "在线验证"),
    LOCAL("local", "本地验证");

    private final String code;
    private final String desc;

    VerifyTypeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
