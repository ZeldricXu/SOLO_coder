package com.solocoder.platform.inference.routing.impl;

import com.solocoder.platform.inference.model.ModelProvider;
import com.solocoder.platform.inference.routing.LoadBalancer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class WeightedRoundRobinLoadBalancer implements LoadBalancer {

    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    @Override
    public Optional<ModelProvider> select(List<ModelProvider> providers, String modelType) {
        List<ModelProvider> eligible = providers.stream()
                .filter(p -> p.getStatus() == ModelProvider.ProviderStatus.ACTIVE)
                .filter(p -> p.getSupportedModels() != null && p.getSupportedModels().contains(modelType))
                .toList();

        if (eligible.isEmpty()) {
            eligible = providers.stream()
                    .filter(p -> p.getStatus() != ModelProvider.ProviderStatus.OFFLINE)
                    .filter(p -> p.getSupportedModels() != null && p.getSupportedModels().contains(modelType))
                    .toList();
        }

        if (eligible.isEmpty()) return Optional.empty();

        int totalWeight = eligible.stream().mapToInt(ModelProvider::getWeight).sum();
        if (totalWeight <= 0) {
            int idx = counters.computeIfAbsent(modelType, k -> new AtomicInteger(0))
                    .getAndIncrement() % eligible.size();
            return Optional.of(eligible.get(idx));
        }

        AtomicInteger counter = counters.computeIfAbsent(modelType, k -> new AtomicInteger(0));
        int pos = Math.abs(counter.getAndIncrement()) % totalWeight;
        int cumulative = 0;
        for (ModelProvider p : eligible) {
            cumulative += p.getWeight();
            if (pos < cumulative) {
                log.debug("Selected provider: id={}, weight={}", p.getProviderId(), p.getWeight());
                return Optional.of(p);
            }
        }
        return Optional.of(eligible.get(eligible.size() - 1));
    }

    @Override
    public String getStrategy() {
        return "WEIGHTED_ROUND_ROBIN";
    }
}
