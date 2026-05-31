package com.web3platform.crosschainbridge.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PoolStatistics {

    private String poolName;
    private int activeCount;
    private int idleCount;
    private int waitCount;
    private long totalCreated;
}
