package com.taskflow.flow.model;

import lombok.Getter;

@Getter
public enum NodeType {
    START("start", "开始节点", true, false),
    END("end", "结束节点", false, true),
    TASK("task", "任务节点", true, true),
    CONDITION("condition", "条件判断", true, true),
    PARALLEL("parallel", "并行网关", true, true),
    WAIT("wait", "等待节点", true, true),
    SUBPROCESS("subprocess", "子流程", true, true),
    NOTIFICATION("notification", "通知节点", true, true);

    private final String code;
    private final String name;
    private final boolean hasOutput;
    private final boolean hasInput;

    NodeType(String code, String name, boolean hasOutput, boolean hasInput) {
        this.code = code;
        this.name = name;
        this.hasOutput = hasOutput;
        this.hasInput = hasInput;
    }

    public static NodeType fromCode(String code) {
        for (NodeType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}
