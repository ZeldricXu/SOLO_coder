package com.designsystem.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

@Getter
public enum TokenType {
    COLOR("color", "颜色"),
    SPACING("spacing", "间距"),
    FONT("font", "字体"),
    BORDER_RADIUS("borderRadius", "圆角"),
    BOX_SHADOW("boxShadow", "阴影"),
    OPACITY("opacity", "透明度"),
    SIZING("sizing", "尺寸"),
    MOTION("motion", "动效");

    @EnumValue
    private final String code;
    private final String name;

    TokenType(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public static TokenType fromCode(String code) {
        for (TokenType type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown token type: " + code);
    }
}
