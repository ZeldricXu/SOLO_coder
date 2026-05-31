package com.chain.infrastructure.gasestimator.dto;

import lombok.Data;
import java.util.List;

@Data
public class BatchGasEstimateResult {

    private List<GasEstimateResult> results;

    private int totalCount;

    private int successCount;

    private int failedCount;

    private long totalTimeMs;
}
