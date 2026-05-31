package com.web3platform.crosschainbridge.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigInteger;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BridgeLockMessage {

    private String lockId;
    private String sourceChain;
    private String targetChain;
    private String tokenAddress;
    private BigInteger amount;
    private String fromAddress;
    private String toAddress;
    private long timestamp;
    private BigInteger nonce;
}
