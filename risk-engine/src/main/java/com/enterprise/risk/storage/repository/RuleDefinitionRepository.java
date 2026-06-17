package com.enterprise.risk.storage.repository;

import com.enterprise.risk.common.rule.RuleDefinition;

import java.util.List;

/**
 * 规则定义仓储接口
 * 从数据库加载规则定义
 */
public interface RuleDefinitionRepository {

    /**
     * 查询所有启用的规则
     */
    List<RuleDefinition> findAllEnabled();

    /**
     * 查询指定时间之后更新过的所有规则
     *
     * @param updatedAtAfter 更新时间戳（毫秒）
     */
    List<RuleDefinition> findByUpdatedAtAfter(long updatedAtAfter);

    /**
     * 查询所有规则的ID和最新更新时间
     * 用于检测规则是否被删除
     */
    List<RuleSummary> findAllSummaries();

    /**
     * 根据ID查询规则
     */
    RuleDefinition findById(String ruleId);

    /**
     * 规则摘要信息
     */
    record RuleSummary(String ruleId, Integer version, Long updatedAt, Boolean enabled) {}
}
