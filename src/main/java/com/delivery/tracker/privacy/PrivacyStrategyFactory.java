package com.delivery.tracker.privacy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 隐私策略工厂
 * 负责策略的注册、发现和获取，支持运行时动态切换
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PrivacyStrategyFactory {

    private final Map<String, PrivacyStrategy> strategyMap = new ConcurrentHashMap<>();
    private final PrivacyStrategy defaultStrategy;

    public PrivacyStrategyFactory(List<PrivacyStrategy> strategies) {
        strategies.forEach(strategy -> {
            strategyMap.put(strategy.getName(), strategy);
            log.info("注册隐私策略: {}", strategy.getName());
        });
        this.defaultStrategy = strategyMap.getOrDefault("LAPLACE", strategies.get(0));
    }

    /**
     * 根据策略名称获取策略
     */
    public PrivacyStrategy getStrategy(String strategyName) {
        if (strategyName == null) {
            return defaultStrategy;
        }
        PrivacyStrategy strategy = strategyMap.get(strategyName);
        if (strategy == null) {
            log.warn("未找到策略: {}, 使用默认策略", strategyName);
            return defaultStrategy;
        }
        return strategy;
    }

    /**
     * 根据场景选择合适的策略
     */
    public PrivacyStrategy getStrategyForScene(String scene) {
        return strategyMap.values().stream()
                .filter(strategy -> strategy.supportsScene(scene))
                .findFirst()
                .orElse(defaultStrategy);
    }

    /**
     * 注册新策略（运行时动态注册）
     */
    public void registerStrategy(PrivacyStrategy strategy) {
        strategyMap.put(strategy.getName(), strategy);
        log.info("动态注册隐私策略: {}", strategy.getName());
    }

    /**
     * 移除策略（运行时动态移除）
     */
    public void unregisterStrategy(String strategyName) {
        PrivacyStrategy removed = strategyMap.remove(strategyName);
        if (removed != null) {
            log.info("移除隐私策略: {}", strategyName);
        }
    }

    /**
     * 获取所有已注册的策略名称
     */
    public List<String> getAllStrategyNames() {
        return List.copyOf(strategyMap.keySet());
    }

    /**
     * 检查策略是否存在
     */
    public boolean hasStrategy(String strategyName) {
        return strategyMap.containsKey(strategyName);
    }
}
