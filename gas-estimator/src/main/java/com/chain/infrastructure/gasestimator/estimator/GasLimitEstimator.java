package com.chain.infrastructure.gasestimator.estimator;

import com.chain.infrastructure.gasestimator.dto.GasEstimateRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class GasLimitEstimator {

    public Mono<Long> estimate(GasEstimateRequest request) {
        return Mono.fromCallable(() -> {
            String txType = request.getTxType() != null ? request.getTxType().toUpperCase() : "TRANSFER";
            return switch (txType) {
                case "TRANSFER" -> 21000L;
                case "ERC20_TRANSFER" -> 65000L;
                case "SWAP" -> 150000L;
                case "DEPLOY" -> 500000L;
                case "MULTISIG" -> 100000L;
                default -> 50000L;
            };
        });
    }
}
