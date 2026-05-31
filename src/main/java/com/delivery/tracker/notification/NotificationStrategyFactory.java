package com.delivery.tracker.notification;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通知策略工厂
 * 管理和调度各种通知策略，支持运行时动态注册和切换
 */
@Slf4j
@Component
public class NotificationStrategyFactory {

    private final Map<String, NotificationStrategy> strategyMap = new ConcurrentHashMap<>();
    private final ApplicationContext applicationContext;

    @Autowired
    public NotificationStrategyFactory(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void init() {
        Map<String, NotificationStrategy> strategies = applicationContext.getBeansOfType(NotificationStrategy.class);
        strategies.values().forEach(this::registerStrategy);
        log.info("初始化通知策略工厂完成, 注册策略: {}", strategyMap.keySet());
    }

    /**
     * 注册策略（运行时可调用）
     */
    public void registerStrategy(NotificationStrategy strategy) {
        strategyMap.put(strategy.getType().toUpperCase(), strategy);
        log.info("注册通知策略: {}", strategy.getType());
    }

    /**
     * 移除策略（运行时可调用）
     */
    public void unregisterStrategy(String type) {
        NotificationStrategy removed = strategyMap.remove(type.toUpperCase());
        if (removed != null) {
            log.info("移除通知策略: {}", type);
        }
    }

    /**
     * 获取策略
     */
    public NotificationStrategy getStrategy(String type) {
        NotificationStrategy strategy = strategyMap.get(type.toUpperCase());
        if (strategy == null) {
            throw new IllegalArgumentException("未找到通知策略: " + type);
        }
        return strategy;
    }

    /**
     * 策略是否存在
     */
    public boolean hasStrategy(String type) {
        return strategyMap.containsKey(type.toUpperCase());
    }

    /**
     * 获取所有策略名称
     */
    public List<String> getAllStrategyNames() {
        return List.copyOf(strategyMap.keySet());
    }

    /**
     * 获取所有策略
     */
    public List<NotificationStrategy> getAllStrategies() {
        return List.copyOf(strategyMap.values());
    }
}
