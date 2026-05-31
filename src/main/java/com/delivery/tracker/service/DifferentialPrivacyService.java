package com.delivery.tracker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.delivery.tracker.entity.PrivacyBudget;
import com.delivery.tracker.mapper.PrivacyBudgetMapper;
import com.delivery.tracker.privacy.PrivacyConfig;
import com.delivery.tracker.privacy.PrivacyConfigManager;
import com.delivery.tracker.privacy.PrivacyStrategy;
import com.delivery.tracker.privacy.PrivacyStrategyFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 差分隐私服务
 * 支持动态配置和可插拔策略，运行时热更新
 * 接口保持向后兼容
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DifferentialPrivacyService {

    private final PrivacyBudgetMapper privacyBudgetMapper;
    private final PrivacyStrategyFactory strategyFactory;
    private final PrivacyConfigManager configManager;

    /**
     * 应用差分隐私（兼容原有接口）
     */
    public Mono<Map<String, Object>> applyDifferentialPrivacy(String userId, Map<String, Object> queryResult, double sensitivity) {
        return applyDifferentialPrivacyWithScene(userId, queryResult, "DEFAULT");
    }

    /**
     * 应用差分隐私（指定场景）
     * 新接口，支持场景化配置
     */
    public Mono<Map<String, Object>> applyDifferentialPrivacyWithScene(String userId, Map<String, Object> queryResult, String scene) {
        return Mono.just(userId)
                .flatMap(this::getOrCreatePrivacyBudget)
                .flatMap(budget -> {
                    PrivacyConfig config = configManager.getConfigForScene(scene);
                    PrivacyStrategy strategy = strategyFactory.getStrategy(config.getStrategyName());

                    if (!checkBudget(budget, strategy.calculateEpsilonCost(config), config.getDelta())) {
                        return Mono.error(new RuntimeException("隐私预算不足"));
                    }

                    Map<String, Object> protectedResult = strategy.applyPrivacy(queryResult, config);
                    consumeBudget(budget, strategy.calculateEpsilonCost(config), config.getDelta());

                    log.debug("差分隐私应用完成, userId={}, scene={}, strategy={}",
                            userId, scene, strategy.getName());
                    return Mono.just(protectedResult);
                });
    }

    /**
     * 应用差分隐私（指定配置ID）
     * 新接口，支持精确配置
     */
    public Mono<Map<String, Object>> applyDifferentialPrivacyWithConfig(String userId, Map<String, Object> queryResult, String configId) {
        return Mono.just(userId)
                .flatMap(this::getOrCreatePrivacyBudget)
                .flatMap(budget -> {
                    PrivacyConfig config = configManager.getConfig(configId);
                    PrivacyStrategy strategy = strategyFactory.getStrategy(config.getStrategyName());

                    if (!checkBudget(budget, strategy.calculateEpsilonCost(config), config.getDelta())) {
                        return Mono.error(new RuntimeException("隐私预算不足"));
                    }

                    Map<String, Object> protectedResult = strategy.applyPrivacy(queryResult, config);
                    consumeBudget(budget, strategy.calculateEpsilonCost(config), config.getDelta());

                    log.debug("差分隐私应用完成, userId={}, configId={}, strategy={}",
                            userId, configId, strategy.getName());
                    return Mono.just(protectedResult);
                });
    }

    /**
     * 应用差分隐私（自定义策略和配置）
     * 新接口，支持运行时动态指定
     */
    public Mono<Map<String, Object>> applyDifferentialPrivacyCustom(String userId, Map<String, Object> queryResult,
                                                                   String strategyName, double epsilon, double delta, double sensitivity) {
        return Mono.just(userId)
                .flatMap(this::getOrCreatePrivacyBudget)
                .flatMap(budget -> {
                    PrivacyStrategy strategy = strategyFactory.getStrategy(strategyName);

                    PrivacyConfig customConfig = PrivacyConfig.builder()
                            .configId("CUSTOM_" + System.currentTimeMillis())
                            .strategyName(strategyName)
                            .epsilon(epsilon)
                            .delta(delta)
                            .sensitivity(sensitivity)
                            .scaleFactor(1.0)
                            .build();

                    if (!checkBudget(budget, epsilon, delta)) {
                        return Mono.error(new RuntimeException("隐私预算不足"));
                    }

                    Map<String, Object> protectedResult = strategy.applyPrivacy(queryResult, customConfig);
                    consumeBudget(budget, epsilon, delta);

                    log.debug("自定义差分隐私应用完成, userId={}, strategy={}", userId, strategyName);
                    return Mono.just(protectedResult);
                });
    }

    private Mono<PrivacyBudget> getOrCreatePrivacyBudget(String userId) {
        return Mono.fromCallable(() -> {
            PrivacyBudget budget = privacyBudgetMapper.selectOne(
                    new LambdaQueryWrapper<PrivacyBudget>()
                            .eq(PrivacyBudget::getUserId, userId)
            );
            if (budget == null) {
                budget = new PrivacyBudget();
                budget.setUserId(userId);
                budget.setEpsilonRemaining(new BigDecimal("10.0"));
                budget.setDeltaRemaining(new BigDecimal("0.001"));
                budget.setTotalQueries(0);
                budget.setLastResetAt(LocalDateTime.now());
                privacyBudgetMapper.insert(budget);
            }
            return budget;
        });
    }

    private boolean checkBudget(PrivacyBudget budget, double epsilon, double delta) {
        return budget.getEpsilonRemaining().compareTo(BigDecimal.valueOf(epsilon)) >= 0
                && budget.getDeltaRemaining().compareTo(BigDecimal.valueOf(delta)) >= 0;
    }

    private void consumeBudget(PrivacyBudget budget, double epsilon, double delta) {
        budget.setEpsilonRemaining(budget.getEpsilonRemaining().subtract(BigDecimal.valueOf(epsilon)));
        budget.setDeltaRemaining(budget.getDeltaRemaining().subtract(BigDecimal.valueOf(delta)));
        budget.setTotalQueries(budget.getTotalQueries() + 1);
        privacyBudgetMapper.updateById(budget);
        log.debug("消费隐私预算: userId={}, epsilon={}, delta={}", budget.getUserId(), epsilon, delta);
    }

    public Mono<PrivacyBudget> getPrivacyBudget(String userId) {
        return Mono.fromCallable(() ->
                privacyBudgetMapper.selectOne(
                        new LambdaQueryWrapper<PrivacyBudget>()
                                .eq(PrivacyBudget::getUserId, userId)
                )
        );
    }

    public Mono<PrivacyBudget> resetPrivacyBudget(String userId) {
        return getOrCreatePrivacyBudget(userId)
                .doOnNext(budget -> {
                    budget.setEpsilonRemaining(new BigDecimal("10.0"));
                    budget.setDeltaRemaining(new BigDecimal("0.001"));
                    budget.setLastResetAt(LocalDateTime.now());
                    privacyBudgetMapper.updateById(budget);
                    log.info("重置隐私预算: userId={}", userId);
                });
    }

    /**
     * 获取当前可用的策略列表（新接口）
     */
    public Mono<java.util.List<String>> getAvailableStrategies() {
        return Mono.just(strategyFactory.getAllStrategyNames());
    }

    /**
     * 获取所有配置（新接口）
     */
    public Mono<Map<String, PrivacyConfig>> getAllConfigs() {
        return Mono.just(configManager.getAllConfigs());
    }

    /**
     * 更新配置（热更新，新接口）
     */
    public Mono<Void> updateConfig(String configId, PrivacyConfig config) {
        return Mono.fromRunnable(() -> configManager.updateConfig(configId, config));
    }

    /**
     * 添加新配置（热更新，新接口）
     */
    public Mono<Void> addConfig(PrivacyConfig config) {
        return Mono.fromRunnable(() -> configManager.addConfig(config));
    }
}
