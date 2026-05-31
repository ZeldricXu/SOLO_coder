package com.didauth.module.indexer.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class TransactionParseRequest implements Serializable {

    private String txHash;
    private Integer txIndex;
    private String fromAddress;
    private String toAddress;
    private String value;
    private String gasPrice;
    private String gasLimit;
    private String gasUsed;
    private String inputData;
    private String status;
    private String contractAddress;
}
