package com.observability.common.enums;

import lombok.Getter;

@Getter
public enum ResourceStatus {

    PROVISIONING("provisioning", "配置中"),
    RUNNING("running", "运行中"),
    COMPLETED("completed", "已完成"),
    FAILED("failed", "失败"),
    STOPPED("stopped", "已停止"),
    CANCELLED("cancelled", "已取消");

    private final String code;
    private final String desc;

    ResourceStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static ResourceStatus fromCode(String code) {
        for (ResourceStatus status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        return null;
    }
}
