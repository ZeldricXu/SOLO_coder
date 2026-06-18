package com.designsystem.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

@Getter
public enum ComponentFramework {
    REACT("react", "React"),
    VUE("vue", "Vue");

    @EnumValue
    private final String code;
    private final String name;

    ComponentFramework(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public static ComponentFramework fromCode(String code) {
        for (ComponentFramework framework : values()) {
            if (framework.getCode().equals(code)) {
                return framework;
            }
        }
        throw new IllegalArgumentException("Unknown framework: " + code);
    }
}
