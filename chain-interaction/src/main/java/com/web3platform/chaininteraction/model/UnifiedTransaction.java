package com.web3platform.chaininteraction.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigInteger;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnifiedTransaction {

    private String chainId;
    private String txHash;
    private long blockNumber;
    private String fromAddr;
    private String toAddr;
    private BigInteger value;
    private long gasUsed;
    private int status;
    private String inputData;
}
