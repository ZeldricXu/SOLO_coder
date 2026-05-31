package com.chainetl.modules.chain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigInteger;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionData {

    private String chainId;

    private String txHash;

    private String fromAddress;

    private String toAddress;

    private BigInteger value;

    private BigInteger gasLimit;

    private BigInteger gasPrice;

    private BigInteger nonce;

    private String inputData;

    private String status;
}
