package com.solocoder.platform.gas.estimator.adapter.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GasEstimationResponseDto {

    private String chainId;
    private String network;
    private Long latestBlock;
    private Long timestamp;
    private String estimationId;

    private GasPriceLevelDto gasPrices;
    private PriorityFeeLevelDto priorityFees;
    private BigDecimal baseFee;
    private NetworkStatusDto networkStatus;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GasPriceLevelDto {
        private BigDecimal low;
        private BigDecimal medium;
        private BigDecimal high;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PriorityFeeLevelDto {
        private BigDecimal low;
        private BigDecimal medium;
        private BigDecimal high;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NetworkStatusDto {
        private Integer pendingTransactions;
        private Long blockGasUsed;
        private Long blockGasLimit;
        private Double gasUtilization;
        private String congestionLevel;
    }
}
