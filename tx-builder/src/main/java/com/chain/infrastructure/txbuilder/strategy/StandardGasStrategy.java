package com.chain.infrastructure.txbuilder.strategy;

import com.chain.infrastructure.txbuilder.dto.TransactionRequest;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;

@Component
public class StandardGasStrategy implements GasOptimizationStrategy {

    @Override
    public String getName() {
        return "STANDARD";
    }

    @Override
    public BigDecimal optimizeGasPrice(TransactionRequest request, BigDecimal currentGasPrice) {
        return currentGasPrice;
    }

    @Override
    public Long optimizeGasLimit(TransactionRequest request, Long estimatedGasLimit) {
        return estimatedGasLimit;
    }

    @Override
    public boolean isApplicable(TransactionRequest request) {
        return true;
    }
}
