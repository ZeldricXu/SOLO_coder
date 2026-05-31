package com.web3platform.chaininteraction.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionReceipt {

    private String txHash;
    private long blockNumber;
    private long gasUsed;
    private int status;
    private List<EventLog> logs;
}
