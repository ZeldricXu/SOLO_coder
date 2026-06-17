package com.enterprise.risk.engine.engine;

import com.enterprise.risk.common.event.RiskEvent;
import com.enterprise.risk.common.rule.RuleEvaluationResult;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 规则执行上下文
 * 保存单次规则评估过程中的状态信息
 */
@Getter
@Builder
public class RuleExecutionContext implements Serializable {

    /**
     * 当前正在评估的风险事件
     */
    private final RiskEvent event;

    /**
     * Redis缓存快照（窗口聚合值、序列状态等外部数据快照）
     * key: 缓存键，如 "window:rule_id:entity_id:agg_field"
     * value: 缓存值
     */
    @Builder.Default
    private final Map<String, Object> cacheSnapshot = new ConcurrentHashMap<>();

    /**
     * 计数器：记录规则评估过程中的各类计数器
     * 用于统计、调试和限流
     */
    @Builder.Default
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    /**
     * 已匹配的规则评估结果列表
     * 按规则执行顺序存储，用于短路判断和加权融合
     */
    @Builder.Default
    private final List<RuleEvaluationResult> matchedResults = Collections.synchronizedList(new ArrayList<>());

    /**
     * 是否已触发短路
     * true表示高优先级规则已命中，后续低优先级规则不再执行
     */
    @Setter
    @Builder.Default
    private volatile boolean shortCircuited = false;

    /**
     * 触发短路的规则ID
     */
    @Setter
    private volatile String shortCircuitRuleId;

    /**
     * 开始时间戳（毫秒）
     */
    @Builder.Default
    private final long startTime = System.currentTimeMillis();

    /**
     * 附加上下文字段：供表达式中 context.xxx 访问
     */
    @Builder.Default
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    /**
     * 增加计数器值
     */
    public long incrementCounter(String name) {
        return counters.computeIfAbsent(name, k -> new AtomicLong(0)).incrementAndGet();
    }

    /**
     * 获取计数器值
     */
    public long getCounter(String name) {
        AtomicLong counter = counters.get(name);
        return counter == null ? 0 : counter.get();
    }

    /**
     * 记录已匹配规则结果
     */
    public void addMatchedResult(RuleEvaluationResult result) {
        matchedResults.add(result);
    }

    /**
     * 获取已匹配规则数量
     */
    public int getMatchedCount() {
        return matchedResults.size();
    }

    /**
     * 获取执行耗时（毫秒）
     */
    public long getElapsedTimeMs() {
        return System.currentTimeMillis() - startTime;
    }

    /**
     * 从缓存快照获取值
     */
    @SuppressWarnings("unchecked")
    public <T> T getCached(String key) {
        return (T) cacheSnapshot.get(key);
    }

    /**
     * 放入缓存快照
     */
    public void putCached(String key, Object value) {
        cacheSnapshot.put(key, value);
    }

    /**
     * 设置附加属性
     */
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    /**
     * 获取附加属性
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }
}
