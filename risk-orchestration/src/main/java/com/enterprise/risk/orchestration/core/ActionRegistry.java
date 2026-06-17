package com.enterprise.risk.orchestration.core;

import com.enterprise.risk.orchestration.action.Action;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动作注册表
 * 管理所有已注册的动作实现，提供动作查找和参数管理功能
 */
@Slf4j
@Component
public class ActionRegistry {

    /**
     * 动作存储 Map<actionId, Action>
     */
    private final Map<String, Action> actionMap = new ConcurrentHashMap<>();

    /**
     * 动作默认参数存储 Map<actionId, Map<paramKey, paramValue>>
     */
    private final Map<String, Map<String, Object>> defaultParametersMap = new ConcurrentHashMap<>();

    /**
     * 注册动作
     *
     * @param action 动作实现
     */
    public void register(Action action) {
        if (action == null) {
            throw new IllegalArgumentException("Action不能为空");
        }
        String actionId = action.getActionId();
        if (actionId == null || actionId.isEmpty()) {
            throw new IllegalArgumentException("Action ID不能为空");
        }
        if (actionMap.containsKey(actionId)) {
            log.warn("[ActionRegistry] 动作ID已存在，将被覆盖: {}", actionId);
        }
        actionMap.put(actionId, action);
        defaultParametersMap.putIfAbsent(actionId, new ConcurrentHashMap<>());
    }

    /**
     * 根据动作ID获取动作
     *
     * @param actionId 动作ID
     * @return 动作实现，不存在返回null
     */
    public Action getAction(String actionId) {
        return actionMap.get(actionId);
    }

    /**
     * 检查动作是否存在
     *
     * @param actionId 动作ID
     * @return true-存在
     */
    public boolean contains(String actionId) {
        return actionMap.containsKey(actionId);
    }

    /**
     * 获取已注册动作数量
     *
     * @return 动作数量
     */
    public int size() {
        return actionMap.size();
    }

    /**
     * 获取所有动作ID列表
     *
     * @return 动作ID列表
     */
    public List<String> getAllActionIds() {
        return new ArrayList<>(actionMap.keySet());
    }

    /**
     * 获取所有动作列表
     *
     * @return 动作列表
     */
    public List<Action> getAllActions() {
        return new ArrayList<>(actionMap.values());
    }

    /**
     * 设置动作默认参数
     *
     * @param actionId   动作ID
     * @param parameters 参数映射
     */
    public void setDefaultParameters(String actionId, Map<String, Object> parameters) {
        if (parameters == null) {
            return;
        }
        if (!actionMap.containsKey(actionId)) {
            log.warn("[ActionRegistry] 设置参数的动作不存在: {}", actionId);
            return;
        }
        defaultParametersMap.put(actionId, new ConcurrentHashMap<>(parameters));
    }

    /**
     * 获取动作默认参数
     *
     * @param actionId 动作ID
     * @return 参数映射
     */
    public Map<String, Object> getDefaultParameters(String actionId) {
        Map<String, Object> params = defaultParametersMap.get(actionId);
        return params != null ? Collections.unmodifiableMap(params) : new HashMap<>();
    }

    /**
     * 合并默认参数和自定义参数
     * 自定义参数会覆盖默认参数中的同名配置
     *
     * @param actionId         动作ID
     * @param customParameters 自定义参数
     * @return 合并后的参数
     */
    public Map<String, Object> mergeParameters(String actionId, Map<String, Object> customParameters) {
        Map<String, Object> merged = new HashMap<>(getDefaultParameters(actionId));
        if (customParameters != null) {
            merged.putAll(customParameters);
        }
        return merged;
    }

    /**
     * 移除动作
     *
     * @param actionId 动作ID
     */
    public void unregister(String actionId) {
        actionMap.remove(actionId);
        defaultParametersMap.remove(actionId);
        log.info("[ActionRegistry] 动作已移除: {}", actionId);
    }

    /**
     * 清空注册表
     */
    public void clear() {
        actionMap.clear();
        defaultParametersMap.clear();
        log.info("[ActionRegistry] 动作注册表已清空");
    }
}
