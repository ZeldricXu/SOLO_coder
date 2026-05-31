package com.chaoslab.modules.mtls.strategy;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class CertificateStrategyRegistry {

    private final List<CertificateStrategy> availableStrategies;

    @Getter
    private final Map<String, CertificateStrategy> strategyMap = new ConcurrentHashMap<>();

    @Getter
    private final Set<String> activeStrategies = ConcurrentHashMap.newKeySet();

    private final ThreadLocal<String> currentStrategy = new ThreadLocal<>();

    @javax.annotation.PostConstruct
    public void init() {
        for (CertificateStrategy strategy : availableStrategies) {
            registerStrategy(strategy);
        }
        activeStrategies.add("DEFAULT");
        activeStrategies.add("AUDIT_LOGGING");
        log.info("Initialized certificate strategy registry with {} strategies: {}",
                strategyMap.size(), strategyMap.keySet());
    }

    public void registerStrategy(CertificateStrategy strategy) {
        if (strategyMap.containsKey(strategy.getName())) {
            log.warn("Strategy {} already registered, overriding", strategy.getName());
        }
        strategyMap.put(strategy.getName(), strategy);
        log.info("Registered certificate strategy: {} (priority={})",
                strategy.getName(), strategy.getPriority());
    }

    public void unregisterStrategy(String strategyName) {
        CertificateStrategy removed = strategyMap.remove(strategyName);
        activeStrategies.remove(strategyName);
        if (removed != null) {
            log.info("Unregistered certificate strategy: {}", strategyName);
        }
    }

    public CertificateStrategy getStrategy(String strategyName) {
        CertificateStrategy strategy = strategyMap.get(strategyName);
        if (strategy == null) {
            throw new IllegalArgumentException("Strategy not found: " + strategyName);
        }
        return strategy;
    }

    public List<CertificateStrategy> getActiveStrategies() {
        return strategyMap.values().stream()
                .filter(s -> activeStrategies.contains(s.getName()) && s.isEnabled())
                .sorted(Comparator.comparingInt(CertificateStrategy::getPriority))
                .collect(Collectors.toList());
    }

    public List<CertificateStrategy> getAllStrategies() {
        return strategyMap.values().stream()
                .sorted(Comparator.comparingInt(CertificateStrategy::getPriority))
                .collect(Collectors.toList());
    }

    public void activateStrategy(String strategyName) {
        if (!strategyMap.containsKey(strategyName)) {
            throw new IllegalArgumentException("Cannot activate unknown strategy: " + strategyName);
        }
        activeStrategies.add(strategyName);
        log.info("Activated certificate strategy: {}", strategyName);
    }

    public void deactivateStrategy(String strategyName) {
        if ("DEFAULT".equals(strategyName)) {
            throw new IllegalArgumentException("Cannot deactivate DEFAULT strategy");
        }
        activeStrategies.remove(strategyName);
        log.info("Deactivated certificate strategy: {}", strategyName);
    }

    public void setCurrentStrategy(String strategyName) {
        currentStrategy.set(strategyName);
    }

    public String getCurrentStrategy() {
        return currentStrategy.get();
    }

    public void clearCurrentStrategy() {
        currentStrategy.remove();
    }

    public List<CertificateStrategy> getExecutionChain(String preferredStrategy) {
        List<CertificateStrategy> chain = new ArrayList<>();

        if (preferredStrategy != null && strategyMap.containsKey(preferredStrategy)) {
            CertificateStrategy preferred = strategyMap.get(preferredStrategy);
            if (preferred.isEnabled()) {
                chain.add(preferred);
                log.debug("Added preferred strategy to execution chain: {}", preferredStrategy);
            }
        }

        for (CertificateStrategy strategy : getActiveStrategies()) {
            if (!chain.contains(strategy)) {
                chain.add(strategy);
            }
        }

        chain.sort(Comparator.comparingInt(CertificateStrategy::getPriority));
        return chain;
    }

    public Map<String, Object> getStrategyStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalStrategies", strategyMap.size());
        stats.put("activeStrategies", activeStrategies.size());
        stats.put("strategyNames", strategyMap.keySet());
        stats.put("activeStrategyNames", activeStrategies);

        List<Map<String, Object>> strategyDetails = new ArrayList<>();
        for (CertificateStrategy strategy : getAllStrategies()) {
            Map<String, Object> detail = new HashMap<>();
            detail.put("name", strategy.getName());
            detail.put("description", strategy.getDescription());
            detail.put("priority", strategy.getPriority());
            detail.put("enabled", strategy.isEnabled());
            detail.put("active", activeStrategies.contains(strategy.getName()));
            strategyDetails.add(detail);
        }
        stats.put("strategies", strategyDetails);

        return stats;
    }
}
