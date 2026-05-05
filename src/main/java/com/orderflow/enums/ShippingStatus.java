package com.orderflow.enums;

import lombok.Getter;

@Getter
public enum ShippingStatus {

    PENDING("pending", "待发货"),
    IN_TRANSIT("in_transit", "运输中"),
    DELIVERED("delivered", "已送达");

    private final String code;
    private final String desc;

    ShippingStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static ShippingStatus getByCode(String code) {
        if (code == null) {
            return null;
        }
        for (ShippingStatus status : values()) {
            if (status.code.equalsIgnoreCase(code)) {
                return status;
            }
        }
        return null;
    }
}
