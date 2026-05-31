package com.chain.infrastructure.txbuilder.strategy;

import com.chain.infrastructure.txbuilder.dto.TransactionRequest;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;

@Component
public class FastGasStrategy implements GasOptimizationStrategy {

    private static final BigDecimal FAST_MULTIPLIER = new BigDecimal("1.2");

    @Override
    public String getName() {
        return "FAST";
    }

    @Override
    public BigDecimal optimizeGasPrice(TransactionRequest request, BigDecimal currentGasPrice) {
        return currentGasPrice.multiply(FAST_MULTIPLIER);
    }

    @Override
    public Long optimizeGasLimit(TransactionRequest request, Long estimatedGasLimit) {
        return (long) (estimatedGasLimit * 1.1);
    }

    @Override
    public boolean isApplicable(TransactionRequest request) {
        return request.getOptions() != null &&
                "FAST".equals(request.getOptions().get("gasPriority"));
    }
}
