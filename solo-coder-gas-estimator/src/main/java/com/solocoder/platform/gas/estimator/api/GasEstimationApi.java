package com.solocoder.platform.gas.estimator.api;

import java.math.BigDecimal;

public interface GasEstimationApi {

    GasPriceEstimate estimateGas(String chainId, String txType, Long gasLimit);

    BigDecimal getRecommendedGasPrice(String chainId, GasPriority priority);

    BigDecimal getBaseFee(String chainId);

    GasPriorityFee getPriorityFeeLevels(String chainId);

    enum GasPriority {
        LOW,
        MEDIUM,
        HIGH
    }

    interface GasPriceEstimate {
        String getChainId();
        BigDecimal getGasPriceLow();
        BigDecimal getGasPriceMedium();
        BigDecimal getGasPriceHigh();
        BigDecimal getBaseFee();
        Long getLatestBlock();
        Long getTimestamp();
    }

    interface GasPriorityFee {
        BigDecimal getLow();
        BigDecimal getMedium();
        BigDecimal getHigh();
    }
}
