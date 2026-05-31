package com.taskflow.core.task.domain;

/**
 * 任务执行阶段枚举
 */
public enum TaskPhase {
    PENDING("pending", "待处理"),
    QUEUED("queued", "已排队"),
    VALIDATING("validating", "参数校验中"),
    EXECUTING("executing", "执行中"),
    PERSISTING("persisting", "结果持久化中"),
    COMPLETED("completed", "已完成"),
    FAILED("failed", "失败");

    private final String code;
    private final String description;

    TaskPhase(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }
}
