package com.observability.common.enums;

import lombok.Getter;

@Getter
public enum RunPhase {

    INITIALIZING("initializing", "初始化"),
    VALIDATING("validating", "验证中"),
    PROCESSING("processing", "处理中"),
    PERSISTING("persisting", "持久化中"),
    COMPLETED("completed", "已完成"),
    FAILED("failed", "失败");

    private final String code;
    private final String desc;

    RunPhase(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static RunPhase fromCode(String code) {
        for (RunPhase phase : values()) {
            if (phase.getCode().equals(code)) {
                return phase;
            }
        }
        return null;
    }
}
