package com.invoice.mgmt.common.enums;

import lombok.Getter;

@Getter
public enum ArchiveTypeEnum {
    ELECTRONIC("electronic", "电子归档"),
    PAPER("paper", "纸质归档");

    private final String code;
    private final String desc;

    ArchiveTypeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
