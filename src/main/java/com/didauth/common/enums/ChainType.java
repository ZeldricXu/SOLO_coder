package com.didauth.common.enums;

import lombok.Getter;

@Getter
public enum ChainType {
    ETH("ETH", "Ethereum", 1),
    BTC("BTC", "Bitcoin", 0),
    POLYGON("POLYGON", "Polygon", 137),
    BSC("BSC", "Binance Smart Chain", 56),
    ARBITRUM("ARBITRUM", "Arbitrum", 42161),
    OPTIMISM("OPTIMISM", "Optimism", 10);

    private final String code;
    private final String name;
    private final Integer chainId;

    ChainType(String code, String name, Integer chainId) {
        this.code = code;
        this.name = name;
        this.chainId = chainId;
    }

    public static ChainType fromCode(String code) {
        for (ChainType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown chain type: " + code);
    }
}
