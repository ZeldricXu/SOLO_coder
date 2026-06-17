package com.enterprise.risk.engine.engine;

import com.enterprise.risk.common.event.RiskEvent;
import com.enterprise.risk.common.rule.RuleDefinition;
import com.enterprise.risk.common.rule.RuleDefinition.WindowConfig;
import com.enterprise.risk.common.rule.RuleEvaluationResult;
import com.enterprise.risk.engine.parser.ExpressionTree.BinaryOpNode;
import com.enterprise.risk.engine.parser.ExpressionTree.ExpressionNode;
import com.enterprise.risk.engine.parser.ExpressionTree.LiteralNode;
import com.enterprise.risk.engine.parser.RuleExpressionEvaluator;
import com.enterprise.risk.storage.service.WindowStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 窗口规则执行器
 * 调用redis WindowStateService获取聚合值，然后执行阈值比较表达式
 * 适用于WINDOW类型的规则
 */
@Component
public class WindowRuleExecutor {

    private static final Logger log = LoggerFactory.getLogger(WindowRuleExecutor.class);

    private final WindowStateService windowStateService;
    private final RuleExpressionEvaluator evaluator;

    @Autowired
    public WindowRuleExecutor(WindowStateService windowStateService) {
        this.windowStateService = windowStateService;
        this.evaluator = new RuleExpressionEvaluator();
    }

    public WindowRuleExecutor(WindowStateService windowStateService,
                              RuleExpressionEvaluator evaluator) {
        this.windowStateService = windowStateService;
        this.evaluator = evaluator != null ? evaluator : new RuleExpressionEvaluator();
    }

    /**
     * 执行窗口规则
     */
    public RuleEvaluationResult execute(CompiledRule compiledRule, RiskEvent event,
                                        RuleExecutionContext context) {
        RuleDefinition ruleDef = compiledRule.getRuleDefinition();
        String ruleId = compiledRule.getRuleId();
        String ruleName = compiledRule.getRuleName();
        WindowConfig windowConfig = ruleDef.getWindowConfig();

        log.debug("开始执行窗口规则: ruleId={}, windowSizeMs={}", ruleId,
                windowConfig != null ? windowConfig.getWindowSizeMs() : null);
        long start = System.currentTimeMillis();

        try {
            if (windowConfig == null) {
                log.warn("规则 {} 缺少窗口配置，返回不匹配", ruleId);
                return RuleEvaluationResult.notMatched(ruleId);
            }

            Map<String, String> groupByValues = extractGroupByValues(windowConfig, event);
            Double aggregatedValue = windowStateService.getAggregatedValue(
                    ruleId, event.getEntityId(), windowConfig, groupByValues, event.getTimestamp());

            if (aggregatedValue == null) {
                aggregatedValue = 0.0;
            }

            String cacheKey = "window:" + ruleId + ":" + event.getEntityId()
                    + ":" + windowConfig.getAggregationField();
            context.putCached(cacheKey, aggregatedValue);

            boolean matched = evaluateThreshold(windowConfig, aggregatedValue);

            if (compiledRule.getCompiledExpression() != null && matched) {
                Map<String, Object> attrs = new HashMap<>();
                attrs.put("windowValue", aggregatedValue);
                attrs.put("aggregatedValue", aggregatedValue);
                RuleExpressionEvaluator customEvaluator = new RuleExpressionEvaluator(attrs);
                matched = customEvaluator.evaluateAsBoolean(compiledRule.getCompiledExpression(), event);
            }

            double fieldValue = extractAggregationFieldValue(windowConfig, event);
            windowStateService.recordEvent(ruleId, event.getEntityId(), windowConfig,
                    groupByValues, fieldValue, event.getTimestamp());

            long cost = System.currentTimeMillis() - start;
            log.debug("窗口规则执行完成: ruleId={}, matched={}, aggregatedValue={}, cost={}ms",
                    ruleId, matched, aggregatedValue, cost);

            context.incrementCounter("window_rule_executed");
            if (matched) {
                context.incrementCounter("window_rule_matched");
                RuleEvaluationResult result = RuleEvaluationResult.matched(ruleId, ruleName);
                result.getContext().put("windowValue", aggregatedValue);
                result.getContext().put("threshold", windowConfig.getThresholdValue());
                result.getContext().put("operator", windowConfig.getOperator());
                result.getMatchedReasons().add(String.format(
                        "窗口聚合值 %.4f %s 阈值 %.4f",
                        aggregatedValue, windowConfig.getOperator(), windowConfig.getThresholdValue()));
                result.setRuleScore(calculateRuleScore(compiledRule, aggregatedValue, windowConfig));
                return result;
            }

            return RuleEvaluationResult.notMatched(ruleId);

        } catch (Exception e) {
            log.error("窗口规则执行异常: ruleId={}", ruleId, e);
            context.incrementCounter("window_rule_error");
            RuleEvaluationResult result = RuleEvaluationResult.notMatched(ruleId);
            result.getMatchedReasons().add("执行异常: " + e.getMessage());
            return result;
        }
    }

    /**
     * 根据操作符和阈值判断是否匹配
     */
    private boolean evaluateThreshold(WindowConfig config, double value) {
        Double threshold = config.getThresholdValue();
        if (threshold == null) {
            return false;
        }
        String operator = config.getOperator() != null ? config.getOperator() : ">=";
        return switch (operator) {
            case ">" -> value > threshold;
            case ">=" -> value >= threshold;
            case "<" -> value < threshold;
            case "<=" -> value <= threshold;
            case "==", "=" -> value == threshold;
            case "!=", "<>" -> value != threshold;
            default -> value >= threshold;
        };
    }

    /**
     * 提取groupBy字段对应的值
     */
    private Map<String, String> extractGroupByValues(WindowConfig config, RiskEvent event) {
        Map<String, String> values = new HashMap<>();
        if (config.getGroupBy() == null) {
            return values;
        }
        for (String field : config.getGroupBy()) {
            Object v = event.getAttribute(field);
            values.put(field, v != null ? v.toString() : "");
        }
        return values;
    }

    /**
     * 提取聚合字段的数值
     */
    private double extractAggregationFieldValue(WindowConfig config, RiskEvent event) {
        String field = config.getAggregationField();
        if (field == null) {
            return 0.0;
        }
        Object v = event.getAttribute(field);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
        return 0.0;
    }

    /**
     * 计算规则分数：聚合值超出阈值越多，分数越高
     */
    private double calculateRuleScore(CompiledRule rule, double value, WindowConfig config) {
        Double baseWeight = rule.getWeight();
        if (baseWeight == null) {
            baseWeight = rule.getRuleDefinition().getModelWeight();
        }
        if (baseWeight == null) {
            baseWeight = 0.5;
        }
        Double threshold = config.getThresholdValue();
        if (threshold == null || threshold == 0) {
            return Math.min(1.0, baseWeight);
        }
        double ratio = value / threshold;
        double score = Math.min(1.0, baseWeight * Math.min(2.0, ratio));
        return Math.min(1.0, Math.max(0.0, score));
    }
}
