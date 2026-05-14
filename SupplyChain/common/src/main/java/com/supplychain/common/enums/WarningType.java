package com.supplychain.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum WarningType {
    LOW_STOCK("low_stock", "低库存预警"),
    OVER_STOCK("over_stock", "高库存预警"),
    SUPPLIER_RISK("supplier_risk", "供应商风险"),
    LOGISTICS_DELAY("logistics_delay", "物流延迟"),
    ORDER_DELAY("order_delay", "订单延迟");

    private final String code;
    private final String desc;
}
