package com.supplychain.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum TrackingStatus {
    PENDING("pending", "待发货"),
    IN_TRANSIT("in_transit", "运输中"),
    ARRIVED("arrived", "已到达"),
    SIGNED("signed", "已签收"),
    DELAYED("delayed", "已延迟");

    private final String code;
    private final String desc;
}
