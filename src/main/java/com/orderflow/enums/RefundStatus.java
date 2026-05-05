package com.orderflow.enums;

import lombok.Getter;

@Getter
public enum RefundStatus {

    PROCESSING("processing", "处理中"),
    SUCCESS("success", "退款成功"),
    FAILED("failed", "退款失败"),
    REJECTED("rejected", "已拒绝");

    private final String code;
    private final String desc;

    RefundStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static RefundStatus getByCode(String code) {
        if (code == null) {
            return null;
        }
        for (RefundStatus status : values()) {
            if (status.code.equalsIgnoreCase(code)) {
                return status;
            }
        }
        return null;
    }
}
