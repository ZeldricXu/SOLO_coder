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
public class LockRequest {

    private String sourceChain;
    private String targetChain;
    private String lockerAddress;
    private BigInteger amount;
    private String assetAddress;
}
