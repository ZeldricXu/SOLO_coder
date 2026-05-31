package com.chain.infrastructure.txbuilder.strategy;

import com.chain.infrastructure.txbuilder.dto.TransactionRequest;

import java.math.BigDecimal;

public interface GasOptimizationStrategy {

    String getName();

    BigDecimal optimizeGasPrice(TransactionRequest request, BigDecimal currentGasPrice);

    Long optimizeGasLimit(TransactionRequest request, Long estimatedGasLimit);

    boolean isApplicable(TransactionRequest request);
}
