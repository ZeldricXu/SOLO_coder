package com.taskplatform.common.enums;

public enum StageType {
    STAGING("staging"),
    PRODUCTION("production"),
    ARCHIVED("archived");

    private final String value;

    StageType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
