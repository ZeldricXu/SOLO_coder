package com.observability.common.enums;

import lombok.Getter;

@Getter
public enum AlertLevel {

    INFO("info", "信息"),
    WARNING("warning", "警告"),
    ERROR("error", "错误"),
    CRITICAL("critical", "严重");

    private final String code;
    private final String desc;

    AlertLevel(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static AlertLevel fromCode(String code) {
        for (AlertLevel level : values()) {
            if (level.getCode().equals(code)) {
                return level;
            }
        }
        return WARNING;
    }
}
