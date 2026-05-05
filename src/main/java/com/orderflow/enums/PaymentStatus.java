package com.orderflow.enums;

import lombok.Getter;

@Getter
public enum PaymentStatus {

    PENDING("pending", "待支付"),
    SUCCESS("success", "支付成功"),
    FAILED("failed", "支付失败"),
    CLOSED("closed", "已关闭");

    private final String code;
    private final String desc;

    PaymentStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static PaymentStatus getByCode(String code) {
        if (code == null) {
            return null;
        }
        for (PaymentStatus status : values()) {
            if (status.code.equalsIgnoreCase(code)) {
                return status;
            }
        }
        return null;
    }
}
