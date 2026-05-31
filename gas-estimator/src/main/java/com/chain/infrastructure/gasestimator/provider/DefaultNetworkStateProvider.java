package com.chain.infrastructure.gasestimator.provider;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

@Component
public class DefaultNetworkStateProvider implements NetworkStateProvider {

    @Override
    public Mono<NetworkState> getCurrentState(String chainType) {
        return Mono.fromCallable(() -> new NetworkState(
                new BigDecimal("1"),
                BigDecimal.ZERO,
                12000L,
                new BigDecimal("0.5")
        ));
    }
}
