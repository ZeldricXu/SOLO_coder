package com.enterprise.risk.common.rule;

public enum RuleType {
    EXPRESSION("expression", "表达式规则"),
    WINDOW("window", "窗口规则"),
    SEQUENCE("sequence", "序列规则");

    private final String code;
    private final String description;

    RuleType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static RuleType fromCode(String code) {
        for (RuleType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown rule type: " + code);
    }
}
