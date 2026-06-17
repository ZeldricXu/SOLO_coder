package com.enterprise.risk.engine.engine;

import com.enterprise.risk.common.event.RiskEvent;
import com.enterprise.risk.common.rule.RuleDefinition;
import com.enterprise.risk.common.rule.RuleEvaluationResult;
import com.enterprise.risk.common.rule.RuleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 规则引擎核心入口
 * - 加载规则，按优先级排序，逐个执行
 * - 支持短路：高优先级匹配后停止后续低优先级
 * - 按业务线和eventType过滤规则
 * - 纯内存执行，异步写入命中日志
 */
@Component
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final RuleCache ruleCache;
    private final ExpressionRuleExecutor expressionExecutor;
    private final WindowRuleExecutor windowExecutor;
    private final SequenceRuleExecutor sequenceExecutor;
    private final RuleShortCircuitManager shortCircuitManager;
    private final RuleWeightedFusion weightedFusion;
    private final Executor asyncExecutor;

    private final AtomicLong totalEvaluated = new AtomicLong(0);
    private final AtomicLong totalMatched = new AtomicLong(0);
    private final AtomicLong totalShortCircuited = new AtomicLong(0);

    @Autowired
    public RuleEngine(RuleCache ruleCache,
                      ExpressionRuleExecutor expressionExecutor,
                      WindowRuleExecutor windowExecutor,
                      SequenceRuleExecutor sequenceExecutor,
                      RuleShortCircuitManager shortCircuitManager,
                      RuleWeightedFusion weightedFusion,
                      @Qualifier("ruleAsyncExecutor") Executor asyncExecutor) {
        this.ruleCache = ruleCache;
        this.expressionExecutor = expressionExecutor;
        this.windowExecutor = windowExecutor;
        this.sequenceExecutor = sequenceExecutor;
        this.shortCircuitManager = shortCircuitManager;
        this.weightedFusion = weightedFusion;
        this.asyncExecutor = asyncExecutor;
    }

    /**
     * 评估单个风险事件，返回所有匹配的规则结果
     *
     * @param event 风险事件
     * @return 所有匹配的规则评估结果（按优先级排序）
     */
    public List<RuleEvaluationResult> evaluate(RiskEvent event) {
        List<CompiledRule> candidates = filterRules(event);
        if (candidates.isEmpty()) {
            log.debug("没有匹配的规则: eventType={}, businessLine={}",
                    event.getEventType(), event.getBusinessLine());
            return List.of();
        }

        candidates.sort(Comparator.comparingInt(CompiledRule::getPriority));

        RuleExecutionContext context = RuleExecutionContext.builder()
                .event(event)
                .build();

        List<RuleEvaluationResult> matched = new ArrayList<>();

        for (CompiledRule rule : candidates) {
            if (context.isShortCircuited()) {
                totalShortCircuited.incrementAndGet();
                log.debug("规则执行被短路: ruleId={}, triggeredBy={}",
                        rule.getRuleId(), context.getShortCircuitRuleId());
                break;
            }

            if (shortCircuitManager.shouldSkip(event, rule)) {
                log.debug("规则被短路管理器跳过: ruleId={}, entityId={}",
                        rule.getRuleId(), event.getEntityId());
                continue;
            }

            RuleEvaluationResult result = executeSingleRule(rule, event, context);
            totalEvaluated.incrementAndGet();

            if (result.isMatched()) {
                totalMatched.incrementAndGet();
                matched.add(result);
                context.addMatchedResult(result);

                if (rule.shouldShortCircuit()) {
                    context.setShortCircuited(true);
                    context.setShortCircuitRuleId(rule.getRuleId());
                    shortCircuitManager.recordShortCircuit(event, rule);
                    log.info("规则触发短路: ruleId={}, priority={}, entityId={}",
                            rule.getRuleId(), rule.getPriority(), event.getEntityId());
                }
            }
        }

        if (!matched.isEmpty()) {
            applyWeightedFusion(matched);
            asyncWriteHitLog(event, matched);
        }

        log.debug("规则评估完成: eventId={}, evaluated={}, matched={}, shortCircuited={}, cost={}ms",
                event.getEventId(), candidates.size(), matched.size(),
                context.isShortCircuited(), context.getElapsedTimeMs());

        return matched;
    }

    /**
     * 按业务线和eventType过滤规则
     */
    private List<CompiledRule> filterRules(RiskEvent event) {
        Collection<CompiledRule> allRules = ruleCache.getAllRules();
        if (allRules == null || allRules.isEmpty()) {
            return List.of();
        }
        String businessLine = event.getBusinessLine();
        String eventType = event.getEventType();

        return allRules.stream()
                .filter(CompiledRule::isEnabled)
                .filter(rule -> matchesBusinessLine(rule.getRuleDefinition(), businessLine))
                .filter(rule -> matchesEventType(rule.getRuleDefinition(), eventType))
                .collect(Collectors.toList());
    }

    private boolean matchesBusinessLine(RuleDefinition rule, String businessLine) {
        if (rule.getBusinessLine() == null || rule.getBusinessLine().equals("*")
                || rule.getBusinessLine().equalsIgnoreCase("ALL")) {
            return true;
        }
        return rule.getBusinessLine().equalsIgnoreCase(businessLine);
    }

    private boolean matchesEventType(RuleDefinition rule, String eventType) {
        List<String> eventTypes = rule.getEventTypes();
        if (eventTypes == null || eventTypes.isEmpty()) {
            return true;
        }
        for (String allowed : eventTypes) {
            if (allowed.equals("*") || allowed.equalsIgnoreCase(eventType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 执行单条规则
     */
    private RuleEvaluationResult executeSingleRule(CompiledRule rule, RiskEvent event,
                                                   RuleExecutionContext context) {
        RuleType type = rule.getRuleDefinition().getRuleType();
        if (type == null) {
            type = RuleType.EXPRESSION;
        }
        return switch (type) {
            case EXPRESSION -> expressionExecutor.execute(rule, event, context);
            case WINDOW -> windowExecutor.execute(rule, event, context);
            case SEQUENCE -> sequenceExecutor.execute(rule, event, context);
        };
    }

    /**
     * 对匹配结果进行加权融合计算最终分数
     */
    private void applyWeightedFusion(List<RuleEvaluationResult> matched) {
        if (matched.size() <= 1) {
            for (RuleEvaluationResult r : matched) {
                r.setFinalScore(r.getRuleScore());
            }
            return;
        }
        List<RuleWeightedFusion.WeightedResult> weighted = matched.stream()
                .map(r -> new RuleWeightedFusion.WeightedResult(
                        r.getRuleId(), r.getRuleScore(),
                        extractWeight(r)))
                .collect(Collectors.toList());
        double finalScore = weightedFusion.fuse(weighted);
        for (RuleEvaluationResult r : matched) {
            r.setFinalScore(finalScore);
        }
    }

    private double extractWeight(RuleEvaluationResult r) {
        Object w = r.getContext().get("weight");
        if (w instanceof Number n) {
            return n.doubleValue();
        }
        Double score = r.getRuleScore();
        return score != null ? score : 1.0;
    }

    /**
     * 异步写入命中日志
     */
    private void asyncWriteHitLog(RiskEvent event, List<RuleEvaluationResult> matched) {
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    writeHitLog(event, matched);
                } catch (Exception e) {
                    log.error("写入命中日志失败", e);
                }
            }, asyncExecutor);
        } catch (Exception e) {
            log.warn("异步任务提交失败，跳过命中日志写入", e);
        }
    }

    /**
     * 写入命中日志（具体实现由存储层提供）
     */
    protected void writeHitLog(RiskEvent event, List<RuleEvaluationResult> matched) {
        log.info("规则命中: eventId={}, entityId={}, rules={}",
                event.getEventId(), event.getEntityId(),
                matched.stream()
                        .map(r -> r.getRuleId() + ":" + String.format("%.2f", r.getFinalScore()))
                        .collect(Collectors.joining(",")));
    }

    /**
     * 获取引擎统计信息
     */
    public Map<String, Long> getStatistics() {
        Map<String, Long> stats = new HashMap<>();
        stats.put("totalEvaluated", totalEvaluated.get());
        stats.put("totalMatched", totalMatched.get());
        stats.put("totalShortCircuited", totalShortCircuited.get());
        stats.put("cachedRules", (long) ruleCache.size());
        return stats;
    }

    /**
     * 重置统计信息
     */
    public void resetStatistics() {
        totalEvaluated.set(0);
        totalMatched.set(0);
        totalShortCircuited.set(0);
    }
}
