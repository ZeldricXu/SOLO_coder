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
public class CrossChainMessage {

    private String messageId;
    private String sourceChain;
    private String targetChain;
    private String sender;
    private String recipient;
    private BigInteger amount;
    private long nonce;
    private long timestamp;
    private String signature;
}
