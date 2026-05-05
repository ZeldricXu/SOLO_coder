package com.orderflow.enums;

import lombok.Getter;

@Getter
public enum PaymentMethod {

    ALIPAY("alipay", "支付宝"),
    WECHAT_PAY("wechat_pay", "微信支付"),
    UNION_PAY("union_pay", "银联支付");

    private final String code;
    private final String desc;

    PaymentMethod(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static PaymentMethod getByCode(String code) {
        if (code == null) {
            return null;
        }
        for (PaymentMethod method : values()) {
            if (method.code.equalsIgnoreCase(code)) {
                return method;
            }
        }
        return null;
    }
}
