package com.supplychain.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ContractStatus {
    DRAFT("draft", "草稿"),
    PENDING_SIGN("pending_sign", "待签署"),
    SIGNED("signed", "已签署"),
    EXPIRED("expired", "已过期"),
    TERMINATED("terminated", "已终止");

    private final String code;
    private final String desc;
}
