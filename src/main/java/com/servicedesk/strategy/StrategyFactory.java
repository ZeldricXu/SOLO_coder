package com.servicedesk.strategy;

import com.servicedesk.config.ServiceDeskProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyFactory {

    private final Map<String, AssignmentStrategy> strategies;
    private final ServiceDeskProperties properties;

    private final Map<String, AssignmentStrategy> customStrategies = new ConcurrentHashMap<>();

    public AssignmentStrategy getStrategy(String strategyName) {
        if (strategyName == null) {
            strategyName = properties.getAssignmentStrategy().getDefaultStrategy();
        }

        if (!properties.getAssignmentStrategy().isStrategyEnabled(strategyName)) {
            log.warn("策略 {} 已禁用，使用默认策略", strategyName);
            strategyName = properties.getAssignmentStrategy().getDefaultStrategy();
        }

        AssignmentStrategy strategy = strategies.get(strategyName);
        if (strategy == null) {
            strategy = customStrategies.get(strategyName);
        }

        if (strategy == null) {
            log.warn("策略 {} 不存在，使用默认策略", strategyName);
            strategy = strategies.get(properties.getAssignmentStrategy().getDefaultStrategy());
        }

        return strategy;
    }

    public AssignmentStrategy getStrategyForTicket(String category, String group) {
        String strategyName = properties.getAssignmentStrategy().getStrategyForTicket(category, group);
        return getStrategy(strategyName);
    }

    public void registerCustomStrategy(AssignmentStrategy strategy) {
        customStrategies.put(strategy.getName(), strategy);
        log.info("已注册自定义策略: {}", strategy.getName());
    }

    public boolean isStrategyAvailable(String strategyName) {
        return strategies.containsKey(strategyName) || customStrategies.containsKey(strategyName);
    }

    public Map<String, AssignmentStrategy> getAllStrategies() {
        Map<String, AssignmentStrategy> all = new ConcurrentHashMap<>(strategies);
        all.putAll(customStrategies);
        return all;
    }
}
