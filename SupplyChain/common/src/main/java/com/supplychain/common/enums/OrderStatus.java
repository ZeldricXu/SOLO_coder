package com.supplychain.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum OrderStatus {
    PENDING_APPROVAL("pending_approval", "待审批"),
    APPROVED("approved", "已审批"),
    CONFIRMED("confirmed", "已确认"),
    REJECTED("rejected", "已拒绝"),
    SHIPPED("shipped", "已发货"),
    RECEIVED("received", "已收货"),
    COMPLETED("completed", "已完成"),
    CANCELLED("cancelled", "已取消");

    private final String code;
    private final String desc;
}
