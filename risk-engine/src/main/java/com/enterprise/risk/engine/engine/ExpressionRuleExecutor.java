package com.enterprise.risk.engine.engine;

import com.enterprise.risk.common.event.RiskEvent;
import com.enterprise.risk.common.rule.RuleEvaluationResult;
import com.enterprise.risk.engine.parser.RuleExpressionEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 表达式规则执行器
 * 直接执行编译后的表达式树，返回boolean匹配结果
 * 适用于EXPRESSION类型的规则
 */
public class ExpressionRuleExecutor {

    private static final Logger log = LoggerFactory.getLogger(ExpressionRuleExecutor.class);

    private final RuleExpressionEvaluator evaluator;

    public ExpressionRuleExecutor() {
        this.evaluator = new RuleExpressionEvaluator();
    }

    public ExpressionRuleExecutor(RuleExpressionEvaluator evaluator) {
        this.evaluator = evaluator != null ? evaluator : new RuleExpressionEvaluator();
    }

    /**
     * 执行表达式规则
     *
     * @param compiledRule 编译后的规则
     * @param event        当前风险事件
     * @param context      执行上下文
     * @return 规则评估结果
     */
    public RuleEvaluationResult execute(CompiledRule compiledRule, RiskEvent event,
                                        RuleExecutionContext context) {
        String ruleId = compiledRule.getRuleId();
        String ruleName = compiledRule.getRuleName();

        log.debug("开始执行表达式规则: ruleId={}, ruleName={}", ruleId, ruleName);
        long start = System.currentTimeMillis();

        try {
            if (compiledRule.getCompiledExpression() == null) {
                log.warn("规则 {} 没有编译后的表达式，直接返回不匹配", ruleId);
                return RuleEvaluationResult.notMatched(ruleId);
            }

            boolean matched = evaluator.evaluateAsBoolean(
                    compiledRule.getCompiledExpression(), event);

            long cost = System.currentTimeMillis() - start;
            log.debug("表达式规则执行完成: ruleId={}, matched={}, cost={}ms", ruleId, matched, cost);

            context.incrementCounter("expr_rule_executed");
            if (matched) {
                context.incrementCounter("expr_rule_matched");
                RuleEvaluationResult result = RuleEvaluationResult.matched(ruleId, ruleName);
                result.getMatchedReasons().add("表达式匹配成功");
                result.setRuleScore(calculateRuleScore(compiledRule));
                return result;
            }

            return RuleEvaluationResult.notMatched(ruleId);

        } catch (Exception e) {
            log.error("表达式规则执行异常: ruleId={}", ruleId, e);
            context.incrementCounter("expr_rule_error");
            RuleEvaluationResult result = RuleEvaluationResult.notMatched(ruleId);
            result.getMatchedReasons().add("执行异常: " + e.getMessage());
            return result;
        }
    }

    /**
     * 根据规则权重计算规则基础分
     */
    private double calculateRuleScore(CompiledRule rule) {
        Double weight = rule.getWeight();
        if (weight == null) {
            weight = rule.getRuleDefinition().getModelWeight();
        }
        if (weight == null) {
            return 1.0;
        }
        return Math.min(1.0, Math.max(0.0, weight));
    }
}
