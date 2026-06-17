package com.enterprise.risk.orchestration.config;

import com.enterprise.risk.orchestration.action.Action;
import com.enterprise.risk.orchestration.core.ActionRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 动作定义解析器
 * 从DB/Redis加载动作配置，注册到动作注册表
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActionDefinitionResolver {

    /**
     * Redis中动作配置存储键
     */
    private static final String ACTION_DEFINITIONS_KEY = "risk:action_definitions";

    private final ActionRegistry actionRegistry;
    private final ApplicationContext applicationContext;
    private final RedissonClient redissonClient;

    /**
     * 初始化时扫描Spring容器中的Action实现并注册
     * 同时从Redis加载自定义动作配置参数
     */
    @PostConstruct
    public void initialize() {
        registerSpringActions();
        loadActionParametersFromRedis();
        log.info("[ActionDefinitionResolver] 动作初始化完成，共注册{}个动作", actionRegistry.size());
    }

    /**
     * 从Spring容器中扫描并注册所有Action Bean
     */
    private void registerSpringActions() {
        Map<String, Action> actionBeans = applicationContext.getBeansOfType(Action.class);
        for (Action action : actionBeans.values()) {
            actionRegistry.register(action);
            log.info("[ActionDefinitionResolver] 注册动作: {} - {}", action.getActionId(), action.getActionName());
        }
    }

    /**
     * 从Redis加载动作配置参数
     * 配置格式: actionId -> Map<paramKey, paramValue>
     */
    @SuppressWarnings("unchecked")
    private void loadActionParametersFromRedis() {
        try {
            RMap<String, Map<String, Object>> actionDefinitions = redissonClient.getMap(ACTION_DEFINITIONS_KEY);
            for (Map.Entry<String, Map<String, Object>> entry : actionDefinitions.entrySet()) {
                String actionId = entry.getKey();
                Map<String, Object> parameters = entry.getValue();
                if (actionRegistry.contains(actionId)) {
                    actionRegistry.setDefaultParameters(actionId, parameters);
                    log.info("[ActionDefinitionResolver] 加载动作参数: {} ({}个参数)", actionId, parameters.size());
                } else {
                    log.warn("[ActionDefinitionResolver] 未找到动作实现，跳过参数加载: {}", actionId);
                }
            }
        } catch (Exception e) {
            log.warn("[ActionDefinitionResolver] 从Redis加载动作参数失败，使用默认配置", e);
        }
    }

    /**
     * 重新加载动作配置（热加载）
     * 当DB中动作配置更新时调用
     */
    public void reloadActionDefinitions() {
        log.info("[ActionDefinitionResolver] 开始重新加载动作配置...");
        loadActionParametersFromRedis();
        log.info("[ActionDefinitionResolver] 动作配置热加载完成");
    }

    /**
     * 获取所有已注册的动作ID列表
     */
    public List<String> getAllRegisteredActionIds() {
        return actionRegistry.getAllActionIds();
    }

    /**
     * 获取指定动作的默认参数
     */
    public Map<String, Object> getActionDefaultParameters(String actionId) {
        return actionRegistry.getDefaultParameters(actionId);
    }
}
