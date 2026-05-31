package com.web3platform.txbuilder.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigInteger;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionBuildRequest {

    private String chainId;
    private String fromAddress;
    private String toAddress;
    private BigInteger value;
    private String data;
    private Long gasLimit;
    private BigInteger gasPrice;
    private Long nonce;
    private String txType;

    public enum TxType {
        LEGACY, EIP1559, EIP2930
    }
}
