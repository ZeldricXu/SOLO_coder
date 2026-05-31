package com.chain.infrastructure.common.enums;

import lombok.Getter;

@Getter
public enum ChainType {

    ETHEREUM("ethereum", 1),
    BSC("bsc", 56),
    POLYGON("polygon", 137),
    ARBITRUM("arbitrum", 42161),
    OPTIMISM("optimism", 10),
    SOLANA("solana", -1),
    AVALANCHE("avalanche", 43114);

    private final String name;
    private final Integer chainId;

    ChainType(String name, Integer chainId) {
        this.name = name;
        this.chainId = chainId;
    }
}
