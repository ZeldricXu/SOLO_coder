package com.chain.infrastructure.txbuilder.service;

import com.chain.infrastructure.txbuilder.dto.TransactionRequest;
import com.chain.infrastructure.txbuilder.strategy.GasOptimizationStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GasOptimizationService {

    private final Map<String, GasOptimizationStrategy> strategyMap;

    public GasOptimizationService(List<GasOptimizationStrategy> strategies) {
        this.strategyMap = strategies.stream()
                .collect(Collectors.toMap(s -> s.getName().toUpperCase(), Function.identity()));
    }

    public Mono<GasOptimizationResult> optimize(TransactionRequest request,
                                                 BigDecimal currentGasPrice,
                                                 Long estimatedGasLimit) {
        return Mono.fromCallable(() -> {
            GasOptimizationStrategy strategy = selectStrategy(request);
            BigDecimal optimizedPrice = strategy.optimizeGasPrice(request, currentGasPrice);
            Long optimizedLimit = strategy.optimizeGasLimit(request, estimatedGasLimit);
            return new GasOptimizationResult(strategy.getName(), optimizedPrice, optimizedLimit);
        });
    }

    private GasOptimizationStrategy selectStrategy(TransactionRequest request) {
        return strategyMap.values().stream()
                .filter(s -> s.isApplicable(request))
                .findFirst()
                .orElse(strategyMap.values().iterator().next());
    }

    public record GasOptimizationResult(
            String strategyName,
            BigDecimal optimizedGasPrice,
            Long optimizedGasLimit
    ) {}
}
