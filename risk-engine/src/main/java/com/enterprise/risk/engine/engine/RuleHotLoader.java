package com.enterprise.risk.engine.engine;

import com.enterprise.risk.common.exception.RuleCompilationException;
import com.enterprise.risk.common.rule.RuleDefinition;
import com.enterprise.risk.common.rule.RuleDefinition.SequenceConfig;
import com.enterprise.risk.common.rule.RuleType;
import com.enterprise.risk.engine.parser.ExpressionTree.ExpressionNode;
import com.enterprise.risk.engine.parser.RuleExpressionCompiler;
import com.enterprise.risk.storage.repository.RuleDefinitionRepository;
import com.enterprise.risk.storage.repository.RuleDefinitionRepository.RuleSummary;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 规则热加载器
 * 定时轮询DB（默认5秒），检测规则变更（版本号/更新时间），
 * 动态更新RuleEngine中的CompiledRule缓存，支持原子替换
 */
@Component
public class RuleHotLoader {

    private static final Logger log = LoggerFactory.getLogger(RuleHotLoader.class);

    private final RuleDefinitionRepository repository;
    private final RuleCache ruleCache;
    private final RuleExpressionCompiler compiler;

    private final long pollIntervalMs;
    private final boolean enabled;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scheduledFuture;
    private volatile long lastUpdateTimestamp = 0;

    @Autowired
    public RuleHotLoader(RuleDefinitionRepository repository,
                         RuleCache ruleCache,
                         RuleExpressionCompiler compiler,
                         @Value("${risk.engine.hotloader.enabled:true}") boolean enabled,
                         @Value("${risk.engine.hotloader.interval-seconds:5}") int intervalSeconds) {
        this.repository = repository;
        this.ruleCache = ruleCache;
        this.compiler = compiler;
        this.enabled = enabled;
        this.pollIntervalMs = intervalSeconds * 1000L;
    }

    /**
     * 初始化：首次全量加载 + 启动定时任务
     */
    @PostConstruct
    public void start() {
        log.info("规则热加载器启动: enabled={}, intervalMs={}", enabled, pollIntervalMs);

        try {
            fullReload();
        } catch (Exception e) {
            log.error("首次全量加载规则失败", e);
        }

        if (enabled) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "rule-hot-loader");
                t.setDaemon(true);
                return t;
            });
            scheduledFuture = scheduler.scheduleWithFixedDelay(
                    this::incrementalReload,
                    pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);
            log.info("规则热加载定时任务已启动");
        }
    }

    /**
     * 停止热加载
     */
    @PreDestroy
    public void stop() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
        }
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("规则热加载器已停止");
    }

    /**
     * 全量重新加载所有规则
     */
    public synchronized void fullReload() {
        log.info("开始全量加载规则...");
        long start = System.currentTimeMillis();

        List<RuleDefinition> rules;
        try {
            rules = repository.findAllEnabled();
        } catch (Exception e) {
            log.error("从数据库加载规则失败", e);
            return;
        }

        if (rules == null || rules.isEmpty()) {
            log.warn("数据库中没有启用的规则");
            ruleCache.clear();
            return;
        }

        Map<String, CompiledRule> compiledMap = new HashMap<>();
        int success = 0;
        int failed = 0;

        for (RuleDefinition rule : rules) {
            try {
                CompiledRule compiled = compileRule(rule);
                compiledMap.put(rule.getRuleId(), compiled);
                success++;
            } catch (Exception e) {
                failed++;
                log.error("编译规则失败: ruleId={}, ruleName={}",
                        rule.getRuleId(), rule.getRuleName(), e);
            }
        }

        ruleCache.putAllRules(compiledMap);
        this.lastUpdateTimestamp = System.currentTimeMillis();

        long cost = System.currentTimeMillis() - start;
        log.info("全量加载规则完成: 总数={}, 成功={}, 失败={}, 耗时={}ms",
                rules.size(), success, failed, cost);
    }

    /**
     * 增量加载：只加载有变更的规则
     */
    public synchronized void incrementalReload() {
        log.debug("开始增量加载规则, lastUpdateTimestamp={}", lastUpdateTimestamp);
        long start = System.currentTimeMillis();

        try {
            List<RuleDefinition> changedRules = repository.findByUpdatedAtAfter(lastUpdateTimestamp);
            List<RuleSummary> allSummaries = repository.findAllSummaries();

            Set<String> dbRuleIds = allSummaries.stream()
                    .filter(s -> Boolean.TRUE.equals(s.enabled()))
                    .map(RuleSummary::ruleId)
                    .collect(Collectors.toSet());

            Collection<CompiledRule> currentRules = ruleCache.getAllRules();
            Set<String> cacheRuleIds = currentRules.stream()
                    .map(CompiledRule::getRuleId)
                    .collect(Collectors.toSet());

            int added = 0;
            int updated = 0;
            int removed = 0;
            int failed = 0;

            if (changedRules != null) {
                for (RuleDefinition rule : changedRules) {
                    if (!Boolean.TRUE.equals(rule.getEnabled())) {
                        if (ruleCache.getRule(rule.getRuleId()) != null) {
                            ruleCache.removeRule(rule.getRuleId());
                            removed++;
                        }
                        continue;
                    }
                    try {
                        CompiledRule compiled = compileRule(rule);
                        boolean isNew = ruleCache.putRule(compiled);
                        if (isNew) {
                            added++;
                        } else {
                            updated++;
                        }
                    } catch (Exception e) {
                        failed++;
                        log.error("编译规则失败: ruleId={}", rule.getRuleId(), e);
                    }
                }
            }

            for (String cachedId : cacheRuleIds) {
                if (!dbRuleIds.contains(cachedId)) {
                    ruleCache.removeRule(cachedId);
                    removed++;
                }
            }

            this.lastUpdateTimestamp = System.currentTimeMillis();
            long cost = System.currentTimeMillis() - start;

            if (added > 0 || updated > 0 || removed > 0 || failed > 0) {
                log.info("增量加载规则完成: 新增={}, 更新={}, 删除={}, 失败={}, 耗时={}ms",
                        added, updated, removed, failed, cost);
            } else {
                log.debug("增量加载规则完成: 无变更, 耗时={}ms", cost);
            }

        } catch (Exception e) {
            log.error("增量加载规则异常", e);
        }
    }

    /**
     * 手动触发单条规则重载
     */
    public boolean reloadRule(String ruleId) {
        log.info("手动重载规则: ruleId={}", ruleId);
        try {
            RuleDefinition rule = repository.findById(ruleId);
            if (rule == null) {
                log.warn("规则不存在: ruleId={}", ruleId);
                ruleCache.removeRule(ruleId);
                return false;
            }
            if (!Boolean.TRUE.equals(rule.getEnabled())) {
                ruleCache.removeRule(ruleId);
                return false;
            }
            CompiledRule compiled = compileRule(rule);
            ruleCache.putRule(compiled);
            this.lastUpdateTimestamp = System.currentTimeMillis();
            return true;
        } catch (Exception e) {
            log.error("手动重载规则失败: ruleId={}", ruleId, e);
            return false;
        }
    }

    /**
     * 编译规则定义为CompiledRule
     */
    private CompiledRule compileRule(RuleDefinition rule) {
        if (rule.getRuleId() == null || rule.getRuleId().trim().isEmpty()) {
            throw new RuleCompilationException("规则ID不能为空");
        }

        ExpressionNode mainExpression = null;
        Map<String, ExpressionNode> stepExpressions = new HashMap<>();

        RuleType type = rule.getRuleType() != null ? rule.getRuleType() : RuleType.EXPRESSION;

        try {
            switch (type) {
                case EXPRESSION -> {
                    if (rule.getDslExpression() != null && !rule.getDslExpression().trim().isEmpty()) {
                        mainExpression = compiler.compile(rule.getDslExpression());
                    }
                }
                case WINDOW -> {
                    if (rule.getDslExpression() != null && !rule.getDslExpression().trim().isEmpty()) {
                        mainExpression = compiler.compile(rule.getDslExpression());
                    }
                }
                case SEQUENCE -> {
                    if (rule.getDslExpression() != null && !rule.getDslExpression().trim().isEmpty()) {
                        mainExpression = compiler.compile(rule.getDslExpression());
                    }
                    SequenceConfig seq = rule.getSequenceConfig();
                    if (seq != null && seq.getEventMappings() != null) {
                        for (SequenceConfig.EventMapping mapping : seq.getEventMappings()) {
                            if (mapping.getCondition() != null && !mapping.getCondition().trim().isEmpty()) {
                                ExpressionNode stepExpr = compiler.compile(mapping.getCondition());
                                stepExpressions.put(mapping.getStepName(), stepExpr);
                            }
                        }
                    }
                }
            }
        } catch (RuleCompilationException e) {
            log.error("规则 {} 的DSL编译失败: {}", rule.getRuleId(), e.getMessage());
            throw e;
        }

        Double weight = rule.getModelWeight() != null ? rule.getModelWeight() : 0.5;

        return CompiledRule.builder()
                .ruleDefinition(rule)
                .compiledExpression(mainExpression)
                .sequenceStepExpressions(stepExpressions.isEmpty() ? null : stepExpressions)
                .weight(weight)
                .build();
    }
}
