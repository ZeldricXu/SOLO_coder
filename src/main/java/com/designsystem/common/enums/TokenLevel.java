package com.designsystem.common.enums;

import lombok.Getter;

@Getter
public enum TokenLevel {
    BASE("base", "基础令牌"),
    SEMANTIC("semantic", "语义化令牌"),
    COMPONENT("component", "组件级令牌");

    private final String code;
    private final String name;

    TokenLevel(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public static TokenLevel fromCode(String code) {
        for (TokenLevel level : values()) {
            if (level.getCode().equals(code)) {
                return level;
            }
        }
        throw new IllegalArgumentException("Unknown token level: " + code);
    }
}
