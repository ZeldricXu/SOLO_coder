package com.formflow.enums;

public enum FieldType {
    TEXT("文本"),
    TEXTAREA("多行文本"),
    NUMBER("数字"),
    DATE("日期"),
    DATETIME("日期时间"),
    SELECT("下拉选择"),
    RADIO("单选"),
    CHECKBOX("多选"),
    FILE("文件"),
    USER("人员选择"),
    DEPARTMENT("部门选择");

    private final String description;

    FieldType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
