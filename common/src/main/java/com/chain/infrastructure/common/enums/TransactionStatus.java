package com.chain.infrastructure.common.enums;

import lombok.Getter;

@Getter
public enum TransactionStatus {

    PENDING("pending", 0),
    SIGNED("signed", 1),
    BROADCAST("broadcast", 2),
    CONFIRMED("confirmed", 3),
    FAILED("failed", -1),
    REJECTED("rejected", -2);

    private final String name;
    private final Integer code;

    TransactionStatus(String name, Integer code) {
        this.name = name;
        this.code = code;
    }
}
