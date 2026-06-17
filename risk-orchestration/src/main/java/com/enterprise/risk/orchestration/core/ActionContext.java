package com.enterprise.risk.orchestration.core;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 动作执行上下文
 * 封装动作执行过程中需要的参数、配置和状态信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionContext implements Serializable {

    /**
     * 编排执行ID
     */
    private String executionId;

    /**
     * 当前执行的动作索引
     */
    private Integer actionIndex;

    /**
     * 总动作数量
     */
    private Integer totalActions;

    /**
     * 动作自定义参数（从DB加载的配置）
     */
    @Builder.Default
    private Map<String, Object> parameters = new HashMap<>();

    /**
     * 前序动作执行结果
     */
    @Builder.Default
    private Map<String, Object> previousResults = new HashMap<>();

    /**
     * 执行开始时间
     */
    @Builder.Default
    private Long startTime = Instant.now().toEpochMilli();

    /**
     * 重试次数
     */
    @Builder.Default
    private Integer retryCount = 0;

    /**
     * 最大重试次数
     */
    @Builder.Default
    private Integer maxRetryCount = 3;

    /**
     * 获取参数值
     *
     * @param key 参数键
     * @param <T> 参数类型
     * @return 参数值
     */
    @SuppressWarnings("unchecked")
    public <T> T getParameter(String key) {
        return (T) parameters.get(key);
    }

    /**
     * 获取参数值（带默认值）
     *
     * @param key          参数键
     * @param defaultValue 默认值
     * @param <T>          参数类型
     * @return 参数值
     */
    @SuppressWarnings("unchecked")
    public <T> T getParameterOrDefault(String key, T defaultValue) {
        return (T) parameters.getOrDefault(key, defaultValue);
    }

    /**
     * 保存动作执行结果
     *
     * @param actionId 动作ID
     * @param result   执行结果
     */
    public void saveResult(String actionId, Object result) {
        this.previousResults.put(actionId, result);
    }

    /**
     * 增加重试次数
     *
     * @return 当前重试次数
     */
    public Integer incrementRetry() {
        return ++this.retryCount;
    }

    /**
     * 是否可以继续重试
     *
     * @return true-可以重试
     */
    public boolean canRetry() {
        return this.retryCount < this.maxRetryCount;
    }
}
