package com.chainetl.modules.chain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BlockData {

    private String chainId;

    private Long blockNumber;

    private String blockHash;

    private String parentHash;

    private Instant timestamp;

    private List<TransactionData> transactions;

    private String rawData;
}
