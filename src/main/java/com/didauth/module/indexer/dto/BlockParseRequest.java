package com.didauth.module.indexer.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class BlockParseRequest implements Serializable {

    private String chainType;
    private Long blockNumber;
    private String blockHash;
    private String parentHash;
    private String miner;
    private Long timestamp;
    private String gasLimit;
    private String gasUsed;
    private String extraData;
    private List<TransactionParseRequest> transactions;
}
