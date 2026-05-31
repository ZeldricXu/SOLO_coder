package com.didauth.module.tx.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class BuildTransactionResponse implements Serializable {

    private String txId;
    private String chainType;
    private String fromAddress;
    private String toAddress;
    private String value;
    private String gasPrice;
    private String gasLimit;
    private String nonce;
    private String data;
    private String rawTransaction;
    private String signType;
    private String multisigWalletId;
}
