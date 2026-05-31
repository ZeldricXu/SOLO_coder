package com.chain.infrastructure.chainindexer.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class BlockData {

    private String chainType;

    private Integer chainId;

    private Long blockNumber;

    private String blockHash;

    private String parentHash;

    private Long timestamp;

    private String miner;

    private BigDecimal difficulty;

    private Long gasUsed;

    private Long gasLimit;

    private List<TransactionData> transactions;

    private String rawData;
}
