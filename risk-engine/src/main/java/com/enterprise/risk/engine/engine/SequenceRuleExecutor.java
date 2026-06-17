package com.enterprise.risk.engine.engine;

import com.enterprise.risk.common.event.RiskEvent;
import com.enterprise.risk.common.rule.RuleDefinition;
import com.enterprise.risk.common.rule.RuleDefinition.SequenceConfig;
import com.enterprise.risk.common.rule.RuleEvaluationResult;
import com.enterprise.risk.engine.parser.RuleExpressionEvaluator;
import com.enterprise.risk.storage.service.SequenceStateService;
import com.enterprise.risk.storage.service.SequenceStateService.SequenceMatchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 序列规则执行器
 * 调用redis SequenceStateService，检查事件序列模式匹配
 * 适用于SEQUENCE类型的规则
 */
@Component
public class SequenceRuleExecutor {

    private static final Logger log = LoggerFactory.getLogger(SequenceRuleExecutor.class);

    private final SequenceStateService sequenceStateService;
    private final RuleExpressionEvaluator evaluator;

    @Autowired
    public SequenceRuleExecutor(SequenceStateService sequenceStateService) {
        this.sequenceStateService = sequenceStateService;
        this.evaluator = new RuleExpressionEvaluator();
    }

    public SequenceRuleExecutor(SequenceStateService sequenceStateService,
                                RuleExpressionEvaluator evaluator) {
        this.sequenceStateService = sequenceStateService;
        this.evaluator = evaluator != null ? evaluator : new RuleExpressionEvaluator();
    }

    /**
     * 执行序列规则
     */
    public RuleEvaluationResult execute(CompiledRule compiledRule, RiskEvent event,
                                        RuleExecutionContext context) {
        RuleDefinition ruleDef = compiledRule.getRuleDefinition();
        String ruleId = compiledRule.getRuleId();
        String ruleName = compiledRule.getRuleName();
        SequenceConfig sequenceConfig = ruleDef.getSequenceConfig();

        log.debug("开始执行序列规则: ruleId={}, pattern={}", ruleId,
                sequenceConfig != null ? sequenceConfig.getPattern() : null);
        long start = System.currentTimeMillis();

        try {
            if (sequenceConfig == null) {
                log.warn("规则 {} 缺少序列配置，返回不匹配", ruleId);
                return RuleEvaluationResult.notMatched(ruleId);
            }

            boolean stepConditionsMet = checkStepConditions(compiledRule, sequenceConfig, event);
            if (!stepConditionsMet) {
                log.debug("规则 {} 的步骤条件不满足，跳过序列检查", ruleId);
                return RuleEvaluationResult.notMatched(ruleId);
            }

            SequenceMatchResult matchResult = sequenceStateService.checkSequence(
                    ruleId, event.getEntityId(), sequenceConfig, event);

            String cacheKey = "sequence:" + ruleId + ":" + event.getEntityId();
            context.putCached(cacheKey, matchResult.currentStep());

            boolean matched = matchResult.matched();

            if (matched && compiledRule.getCompiledExpression() != null) {
                Map<String, Object> attrs = new HashMap<>(matchResult.context() != null
                        ? matchResult.context() : Map.of());
                attrs.put("step", matchResult.currentStep());
                attrs.put("matchedEventCount", matchResult.matchedEvents() != null
                        ? matchResult.matchedEvents().size() : 0);
                RuleExpressionEvaluator customEvaluator = new RuleExpressionEvaluator(attrs);
                matched = customEvaluator.evaluateAsBoolean(
                        compiledRule.getCompiledExpression(), event);
            }

            long cost = System.currentTimeMillis() - start;
            log.debug("序列规则执行完成: ruleId={}, matched={}, currentStep={}, cost={}ms",
                    ruleId, matched, matchResult.currentStep(), cost);

            context.incrementCounter("seq_rule_executed");
            if (matched) {
                context.incrementCounter("seq_rule_matched");
                RuleEvaluationResult result = RuleEvaluationResult.matched(ruleId, ruleName);
                if (matchResult.matchedEvents() != null) {
                    result.setMatchedEvents(matchResult.matchedEvents());
                }
                if (matchResult.context() != null) {
                    result.setContext(new HashMap<>(matchResult.context()));
                }
                result.getContext().put("currentStep", matchResult.currentStep());
                result.getContext().put("pattern", sequenceConfig.getPattern());
                result.getMatchedReasons().add(String.format(
                        "序列模式匹配成功: pattern=%s, completedSteps=%d",
                        sequenceConfig.getPattern(), matchResult.currentStep()));
                result.setRuleScore(calculateRuleScore(compiledRule, matchResult));
                return result;
            }

            return RuleEvaluationResult.notMatched(ruleId);

        } catch (Exception e) {
            log.error("序列规则执行异常: ruleId={}", ruleId, e);
            context.incrementCounter("seq_rule_error");
            RuleEvaluationResult result = RuleEvaluationResult.notMatched(ruleId);
            result.getMatchedReasons().add("执行异常: " + e.getMessage());
            return result;
        }
    }

    /**
     * 检查步骤条件（如果配置了的话）
     */
    private boolean checkStepConditions(CompiledRule compiledRule,
                                        SequenceConfig config, RiskEvent event) {
        if (config.getEventMappings() == null || config.getEventMappings().isEmpty()) {
            return true;
        }
        if (compiledRule.getSequenceStepExpressions() == null
                || compiledRule.getSequenceStepExpressions().isEmpty()) {
            return true;
        }
        for (SequenceConfig.EventMapping mapping : config.getEventMappings()) {
            if (!event.getEventType().equalsIgnoreCase(mapping.getEventType())) {
                continue;
            }
            String condition = mapping.getCondition();
            if (condition == null || condition.trim().isEmpty()) {
                return true;
            }
            var expr = compiledRule.getSequenceStepExpressions().get(mapping.getStepName());
            if (expr == null) {
                return true;
            }
            return evaluator.evaluateAsBoolean(expr, event);
        }
        return true;
    }

    /**
     * 计算规则分数：基于完成的步骤数和权重
     */
    private double calculateRuleScore(CompiledRule rule, SequenceMatchResult result) {
        Double baseWeight = rule.getWeight();
        if (baseWeight == null) {
            baseWeight = rule.getRuleDefinition().getModelWeight();
        }
        if (baseWeight == null) {
            baseWeight = 0.5;
        }
        SequenceConfig config = rule.getRuleDefinition().getSequenceConfig();
        int totalSteps = 1;
        if (config != null && config.getPattern() != null) {
            totalSteps = Math.max(1, config.getPattern().split("->").length);
        }
        double stepRatio = Math.min(1.0, (double) result.currentStep() / totalSteps);
        double score = Math.min(1.0, baseWeight * (0.5 + 0.5 * stepRatio));
        return Math.min(1.0, Math.max(0.0, score));
    }
}
