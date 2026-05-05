package com.paygateway.enums;

import lombok.Getter;

@Getter
public enum OrderStatus {
    
    PENDING("pending", "待支付"),
    PAID("paid", "已支付"),
    FAILED("failed", "支付失败"),
    CLOSED("closed", "已关闭"),
    REFUNDED("refunded", "已退款"),
    PARTIAL_REFUNDED("partial_refunded", "部分退款");
    
    private final String code;
    private final String description;
    
    OrderStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }
    
    public static OrderStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (OrderStatus status : values()) {
            if (status.code.equalsIgnoreCase(code)) {
                return status;
            }
        }
        return null;
    }
    
    public static boolean isValidStatus(String code) {
        return fromCode(code) != null;
    }
    
    public static boolean isPaid(OrderStatus status) {
        return PAID.equals(status) || REFUNDED.equals(status) || PARTIAL_REFUNDED.equals(status);
    }
    
    public static boolean canPay(OrderStatus status) {
        return PENDING.equals(status);
    }
    
    public static boolean canRefund(OrderStatus status) {
        return PAID.equals(status) || PARTIAL_REFUNDED.equals(status);
    }
}
