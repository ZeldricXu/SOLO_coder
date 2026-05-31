package com.solocoder.platform.gas.estimator.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GasEstimation {

    private Long id;
    private String chainId;
    private String network;
    private GasPriceLevel gasPrices;
    private BigDecimal baseFee;
    private PriorityFeeLevel priorityFees;
    private NetworkStatus networkStatus;
    private Long latestBlock;
    private Long timestamp;
    private String signature;
    private String estimationId;
    private LocalDateTime createdAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GasPriceLevel {
        private BigDecimal low;
        private BigDecimal medium;
        private BigDecimal high;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PriorityFeeLevel {
        private BigDecimal low;
        private BigDecimal medium;
        private BigDecimal high;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NetworkStatus {
        private Integer pendingTransactions;
        private Long blockGasUsed;
        private Long blockGasLimit;
        private Double gasUtilization;
        private CongestionLevel congestionLevel;

        public enum CongestionLevel {
            LOW,
            NORMAL,
            HIGH,
            CONGESTED
        }
    }
}
