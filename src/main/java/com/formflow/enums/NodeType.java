package com.formflow.enums;

public enum NodeType {
    START("开始节点"),
    APPROVAL("审批节点"),
    CONDITION("条件节点"),
    PARALLEL("并行节点"),
    END("结束节点");

    private final String description;

    NodeType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
