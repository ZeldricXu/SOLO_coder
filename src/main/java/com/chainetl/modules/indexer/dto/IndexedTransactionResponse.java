package com.chainetl.modules.indexer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigInteger;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexedTransactionResponse {

    private String txId;
    private String chainId;
    private Long blockNumber;
    private String txHash;
    private String fromAddress;
    private String toAddress;
    private BigInteger value;
    private Long gasUsed;
    private Long gasPrice;
    private String status;
    private String inputData;
    private Instant indexedAt;
}
