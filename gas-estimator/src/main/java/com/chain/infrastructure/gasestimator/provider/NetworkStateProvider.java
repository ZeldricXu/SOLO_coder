package com.chain.infrastructure.gasestimator.provider;

import com.chain.infrastructure.gasestimator.dto.GasEstimateRequest;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

public interface NetworkStateProvider {

    Mono<NetworkState> getCurrentState(String chainType);

    record NetworkState(
            BigDecimal currentBaseFee,
            BigDecimal pendingTransactions,
            Long blockTime,
            BigDecimal gasUtilizationRate
    ) {}
}
