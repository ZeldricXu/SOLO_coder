package com.chain.infrastructure.chainindexer.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class TransactionData {

    private String txHash;

    private Integer txIndex;

    private String fromAddress;

    private String toAddress;

    private BigDecimal value;

    private BigDecimal gasPrice;

    private Long gasUsed;

    private String inputData;

    private Integer status;

    private String contractAddress;
}
