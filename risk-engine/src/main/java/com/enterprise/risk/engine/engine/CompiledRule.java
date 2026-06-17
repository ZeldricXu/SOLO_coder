package com.enterprise.risk.engine.engine;

import com.enterprise.risk.common.rule.RuleDefinition;
import com.enterprise.risk.engine.parser.ExpressionTree.ExpressionNode;
import lombok.Builder;
import lombok.Getter;

import java.io.Serializable;

/**
 * 编译后的规则对象
 * 包含原始RuleDefinition定义 + 编译后ExpressionNode表达式树 + 权重配置
 * 避免运行时重复编译，提升规则执行性能
 */
@Getter
@Builder
public class CompiledRule implements Serializable {

    /**
     * 原始规则定义
     */
    private final RuleDefinition ruleDefinition;

    /**
     * 编译后的主表达式树节点
     * 对于EXPRESSION类型规则：为dslExpression编译结果
     * 对于WINDOW类型规则：为阈值比较表达式编译结果
     * 对于SEQUENCE类型规则：为序列匹配后的附加条件表达式（可为null）
     */
    private final ExpressionNode compiledExpression;

    /**
     * 序列规则各步骤的条件表达式编译结果
     * key: stepName
     */
    private final java.util.Map<String, ExpressionNode> sequenceStepExpressions;

    /**
     * 规则权重，用于加权融合计算
     * 默认取RuleDefinition.modelWeight
     */
    private final Double weight;

    /**
     * 规则ID（便捷访问）
     */
    public String getRuleId() {
        return ruleDefinition.getRuleId();
    }

    /**
     * 规则名称（便捷访问）
     */
    public String getRuleName() {
        return ruleDefinition.getRuleName();
    }

    /**
     * 规则优先级（便捷访问），数值越小优先级越高
     */
    public Integer getPriority() {
        return ruleDefinition.getPriority();
    }

    /**
     * 是否需要短路（便捷访问）
     */
    public boolean shouldShortCircuit() {
        return ruleDefinition.shouldShortCircuit();
    }

    /**
     * 规则是否启用（便捷访问）
     */
    public boolean isEnabled() {
        return ruleDefinition.isEnabled();
    }
}
