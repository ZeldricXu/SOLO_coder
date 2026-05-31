package com.chainetl.modules.gas.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GasEstimateResponse {

    private String estimateId;
    private String chainId;
    private String transactionType;
    private Long estimatedGas;
    private GasPriceLevel gasPrice;
    private PriorityFeeLevel priorityFee;
    private Double confidenceLevel;
    private Map<String, Object> historicalData;
    private Instant createdAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GasPriceLevel {
        private Long low;
        private Long medium;
        private Long high;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PriorityFeeLevel {
        private Long low;
        private Long medium;
        private Long high;
    }
}
