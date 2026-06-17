package com.enterprise.risk.storage.service;

import com.enterprise.risk.common.rule.RuleDefinition.WindowConfig;

import java.util.Map;

/**
 * 窗口状态服务
 * 基于Redis维护滑动窗口内的聚合值（SUM、AVG、COUNT、MAX、MIN、DISTINCT_COUNT）
 */
public interface WindowStateService {

    /**
     * 获取窗口聚合值
     *
     * @param ruleId           规则ID
     * @param entityId         实体ID
     * @param windowConfig     窗口配置
     * @param groupByValues    groupBy字段对应的值
     * @param currentTimestamp 当前时间戳
     * @return 聚合结果值
     */
    Double getAggregatedValue(String ruleId, String entityId, WindowConfig windowConfig,
                              Map<String, String> groupByValues, long currentTimestamp);

    /**
     * 将当前事件纳入窗口计算（异步写入）
     */
    void recordEvent(String ruleId, String entityId, WindowConfig windowConfig,
                     Map<String, String> groupByValues, double fieldValue, long timestamp);

    /**
     * 清理过期窗口数据
     */
    void cleanupExpired(String ruleId, long cutoffTimestamp);
}
