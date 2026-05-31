package com.chain.infrastructure.txbuilder.strategy;

import com.chain.infrastructure.txbuilder.dto.TransactionRequest;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;

@Component
public class EconomicGasStrategy implements GasOptimizationStrategy {

    private static final BigDecimal ECONOMIC_MULTIPLIER = new BigDecimal("0.8");

    @Override
    public String getName() {
        return "ECONOMIC";
    }

    @Override
    public BigDecimal optimizeGasPrice(TransactionRequest request, BigDecimal currentGasPrice) {
        return currentGasPrice.multiply(ECONOMIC_MULTIPLIER);
    }

    @Override
    public Long optimizeGasLimit(TransactionRequest request, Long estimatedGasLimit) {
        return estimatedGasLimit;
    }

    @Override
    public boolean isApplicable(TransactionRequest request) {
        return request.getOptions() != null &&
                "ECONOMIC".equals(request.getOptions().get("gasPriority"));
    }
}
