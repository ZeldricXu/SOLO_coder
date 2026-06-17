package com.enterprise.risk.storage.service;

import com.enterprise.risk.common.event.RiskEvent;
import com.enterprise.risk.common.rule.RuleDefinition.SequenceConfig;

import java.util.List;
import java.util.Map;

/**
 * 序列状态服务
 * 基于Redis维护事件序列模式匹配的中间状态
 */
public interface SequenceStateService {

    /**
     * 序列匹配结果
     */
    record SequenceMatchResult(
            boolean matched,
            int currentStep,
            List<RiskEvent> matchedEvents,
            Map<String, Object> context
    ) {}

    /**
     * 检查事件序列是否匹配指定模式
     *
     * @param ruleId        规则ID
     * @param entityId      实体ID（按实体维度维护序列状态）
     * @param sequenceConfig 序列配置
     * @param event         当前事件
     * @return 序列匹配结果
     */
    SequenceMatchResult checkSequence(String ruleId, String entityId,
                                      SequenceConfig sequenceConfig, RiskEvent event);

    /**
     * 清理指定规则的所有序列状态
     */
    void clearStates(String ruleId);

    /**
     * 清理指定实体的序列状态
     */
    void clearEntityState(String ruleId, String entityId);

    /**
     * 清理超时的序列状态
     */
    void cleanupExpired(long cutoffTimestamp);
}
