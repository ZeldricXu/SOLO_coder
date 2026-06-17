package com.designsystem.common.enums;

import lombok.Getter;

@Getter
public enum ExportFormat {
    CSS("css", "CSS Variables"),
    JS("js", "JavaScript Module"),
    JSON("json", "JSON"),
    SCSS("scss", "SCSS Variables"),
    LESS("less", "LESS Variables"),
    ANDROID("android", "Android XML"),
    IOS("ios", "iOS Swift");

    private final String code;
    private final String name;

    ExportFormat(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public static ExportFormat fromCode(String code) {
        for (ExportFormat format : values()) {
            if (format.getCode().equals(code)) {
                return format;
            }
        }
        throw new IllegalArgumentException("Unknown export format: " + code);
    }
}
