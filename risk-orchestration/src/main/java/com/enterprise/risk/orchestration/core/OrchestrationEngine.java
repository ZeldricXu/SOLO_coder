package com.enterprise.risk.orchestration.core;

import com.enterprise.risk.common.alert.AlertEvent;
import com.enterprise.risk.common.rule.RuleDefinition;
import com.enterprise.risk.orchestration.action.Action;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 编排引擎
 * 接收告警事件，根据规则定义获取动作列表，按顺序异步执行所有动作
 * 支持执行结果记录和失败重试机制
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrchestrationEngine {

    /**
     * 规则定义Redis缓存键
     */
    private static final String RULE_DEFINITIONS_KEY = "risk:rule_definitions";

    /**
     * 编排执行结果Topic
     */
    private static final String ORCHESTRATION_RESULT_TOPIC = "risk.orchestration.result";

    /**
     * 告警事件输入Topic
     */
    private static final String ALERT_EVENT_TOPIC = "risk.alert.events";

    /**
     * 规则定义本地缓存
     */
    private final Map<String, RuleDefinition> ruleCache = new ConcurrentHashMap<>();

    private final ActionRegistry actionRegistry;
    private final RedissonClient redissonClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    private Counter orchestrationTotalCounter;
    private Counter orchestrationSuccessCounter;
    private Counter orchestrationFailedCounter;
    private Counter actionExecuteCounter;
    private Counter actionSuccessCounter;
    private Counter actionFailedCounter;
    private Timer orchestrationTimer;

    @PostConstruct
    public void initMetrics() {
        orchestrationTotalCounter = Counter.builder("risk_orchestration_total")
                .description("编排执行总数")
                .register(meterRegistry);
        orchestrationSuccessCounter = Counter.builder("risk_orchestration_success")
                .description("编排执行成功数")
                .register(meterRegistry);
        orchestrationFailedCounter = Counter.builder("risk_orchestration_failed")
                .description("编排执行失败数")
                .register(meterRegistry);
        actionExecuteCounter = Counter.builder("risk_action_execute_total")
                .description("动作执行总数")
                .register(meterRegistry);
        actionSuccessCounter = Counter.builder("risk_action_success")
                .description("动作执行成功数")
                .register(meterRegistry);
        actionFailedCounter = Counter.builder("risk_action_failed")
                .description("动作执行失败数")
                .register(meterRegistry);
        orchestrationTimer = Timer.builder("risk_orchestration_duration")
                .description("编排执行耗时")
                .register(meterRegistry);
    }

    /**
     * 监听Kafka告警事件并触发编排
     */
    @KafkaListener(topics = ALERT_EVENT_TOPIC, groupId = "risk-orchestration")
    public void onAlertEvent(AlertEvent alertEvent) {
        if (alertEvent == null) {
            return;
        }
        log.info("[OrchestrationEngine] 接收到告警事件: alertId={}, ruleId={}", alertEvent.getAlertId(), alertEvent.getRuleId());
        orchestrate(alertEvent);
    }

    /**
     * 执行编排（异步入口）
     *
     * @param alertEvent 告警事件
     */
    @Async("orchestrationExecutor")
    public void orchestrate(AlertEvent alertEvent) {
        long startTime = System.currentTimeMillis();
        String executionId = UUID.randomUUID().toString();
        orchestrationTotalCounter.increment();

        try {
            List<String> actionIds = resolveActionIds(alertEvent);
            if (actionIds == null || actionIds.isEmpty()) {
                log.info("[OrchestrationEngine] 告警事件无关联动作，跳过编排: alertId={}", alertEvent.getAlertId());
                orchestrationSuccessCounter.increment();
                recordResult(executionId, alertEvent, new ArrayList<>(), true, System.currentTimeMillis() - startTime);
                return;
            }

            List<ActionExecutionResult> results = executeActions(executionId, alertEvent, actionIds);
            boolean allSuccess = results.stream().allMatch(ActionExecutionResult::isSuccess);

            if (allSuccess) {
                orchestrationSuccessCounter.increment();
                log.info("[OrchestrationEngine] 编排执行成功: executionId={}, alertId={}, 耗时={}ms",
                        executionId, alertEvent.getAlertId(), System.currentTimeMillis() - startTime);
            } else {
                orchestrationFailedCounter.increment();
                log.warn("[OrchestrationEngine] 编排执行存在失败动作: executionId={}, alertId={}",
                        executionId, alertEvent.getAlertId());
            }

            recordResult(executionId, alertEvent, results, allSuccess, System.currentTimeMillis() - startTime);
            orchestrationTimer.record(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS);

        } catch (Exception e) {
            orchestrationFailedCounter.increment();
            log.error("[OrchestrationEngine] 编排执行异常: executionId={}, alertId={}", executionId, alertEvent.getAlertId(), e);
            recordResult(executionId, alertEvent, new ArrayList<>(), false, System.currentTimeMillis() - startTime);
        }
    }

    /**
     * 解析告警事件关联的动作ID列表
     * 优先从事件本身获取，其次从规则定义缓存中获取
     */
    private List<String> resolveActionIds(AlertEvent alertEvent) {
        if (alertEvent.getActions() != null && !alertEvent.getActions().isEmpty()) {
            return alertEvent.getActions();
        }

        String ruleId = alertEvent.getRuleId();
        if (ruleId == null) {
            return new ArrayList<>();
        }

        RuleDefinition ruleDefinition = getRuleDefinition(ruleId);
        if (ruleDefinition == null) {
            log.warn("[OrchestrationEngine] 未找到规则定义: ruleId={}", ruleId);
            return new ArrayList<>();
        }

        return ruleDefinition.getActionIds() != null ? ruleDefinition.getActionIds() : new ArrayList<>();
    }

    /**
     * 获取规则定义（本地缓存 + Redis缓存）
     */
    @SuppressWarnings("unchecked")
    private RuleDefinition getRuleDefinition(String ruleId) {
        RuleDefinition cached = ruleCache.get(ruleId);
        if (cached != null) {
            return cached;
        }

        try {
            RMap<String, RuleDefinition> ruleMap = redissonClient.getMap(RULE_DEFINITIONS_KEY);
            RuleDefinition rule = ruleMap.get(ruleId);
            if (rule != null) {
                ruleCache.put(ruleId, rule);
            }
            return rule;
        } catch (Exception e) {
            log.error("[OrchestrationEngine] 从Redis获取规则定义失败: ruleId={}", ruleId, e);
            return null;
        }
    }

    /**
     * 按顺序执行动作列表
     *
     * @param executionId 执行ID
     * @param alertEvent  告警事件
     * @param actionIds   动作ID列表
     * @return 各动作执行结果
     */
    private List<ActionExecutionResult> executeActions(String executionId, AlertEvent alertEvent, List<String> actionIds) {
        List<ActionExecutionResult> results = new ArrayList<>();
        ActionContext sharedContext = ActionContext.builder()
                .executionId(executionId)
                .totalActions(actionIds.size())
                .build();

        for (int i = 0; i < actionIds.size(); i++) {
            String actionId = actionIds.get(i);
            sharedContext.setActionIndex(i);

            Map<String, Object> parameters = actionRegistry.getDefaultParameters(actionId);
            sharedContext.setParameters(parameters);

            ActionExecutionResult result = executeWithRetry(actionId, alertEvent, sharedContext);
            results.add(result);

            if (result.isSuccess()) {
                sharedContext.saveResult(actionId, result.getResult());
            }
        }

        return results;
    }

    /**
     * 带重试机制执行单个动作
     */
    private ActionExecutionResult executeWithRetry(String actionId, AlertEvent alertEvent, ActionContext context) {
        Action action = actionRegistry.getAction(actionId);
        if (action == null) {
            log.warn("[OrchestrationEngine] 动作不存在: actionId={}", actionId);
            return ActionExecutionResult.failure(actionId, "ACTION_NOT_FOUND", null);
        }

        actionExecuteCounter.increment();
        int maxRetry = context.getMaxRetryCount();

        while (true) {
            try {
                long start = System.currentTimeMillis();
                boolean success = action.execute(alertEvent, context);
                long duration = System.currentTimeMillis() - start;

                if (success) {
                    actionSuccessCounter.increment();
                    return ActionExecutionResult.success(actionId, duration, context.getPreviousResults().get(actionId));
                } else {
                    if (context.canRetry()) {
                        context.incrementRetry();
                        long waitMs = (long) Math.pow(2, context.getRetryCount()) * 100;
                        log.info("[OrchestrationEngine] 动作执行失败，等待{}ms后重试({}/{}): actionId={}",
                                waitMs, context.getRetryCount(), maxRetry, actionId);
                        Thread.sleep(waitMs);
                        continue;
                    }
                    actionFailedCounter.increment();
                    return ActionExecutionResult.failure(actionId, "EXECUTION_FAILED", duration);
                }
            } catch (Exception e) {
                if (context.canRetry()) {
                    context.incrementRetry();
                    long waitMs = (long) Math.pow(2, context.getRetryCount()) * 100;
                    log.warn("[OrchestrationEngine] 动作执行异常，等待{}ms后重试({}/{}): actionId={}",
                            waitMs, context.getRetryCount(), maxRetry, actionId, e);
                    try {
                        Thread.sleep(waitMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    actionFailedCounter.increment();
                    log.error("[OrchestrationEngine] 动作执行异常，重试耗尽: actionId={}", actionId, e);
                    return ActionExecutionResult.failure(actionId, e.getMessage(), null);
                }
            }
        }

        return ActionExecutionResult.failure(actionId, "INTERRUPTED", null);
    }

    /**
     * 记录编排执行结果
     */
    private void recordResult(String executionId, AlertEvent alertEvent, List<ActionExecutionResult> actionResults,
                              boolean success, long durationMs) {
        Map<String, Object> result = new HashMap<>();
        result.put("execution_id", executionId);
        result.put("alert_id", alertEvent.getAlertId());
        result.put("rule_id", alertEvent.getRuleId());
        result.put("entity_id", alertEvent.getEntityId());
        result.put("business_line", alertEvent.getBusinessLine());
        result.put("success", success);
        result.put("duration_ms", durationMs);
        result.put("action_count", actionResults.size());
        result.put("success_action_count", actionResults.stream().filter(ActionExecutionResult::isSuccess).count());
        result.put("action_results", actionResults);
        result.put("executed_at", Instant.now().toEpochMilli());

        try {
            kafkaTemplate.send(ORCHESTRATION_RESULT_TOPIC, executionId, result);
        } catch (Exception e) {
            log.error("[OrchestrationEngine] 发送编排结果失败: executionId={}", executionId, e);
        }
    }

    /**
     * 刷新规则缓存（热加载触发）
     */
    public void refreshRuleCache(String ruleId) {
        if (ruleId != null) {
            ruleCache.remove(ruleId);
            log.info("[OrchestrationEngine] 规则缓存已刷新: ruleId={}", ruleId);
        } else {
            ruleCache.clear();
            log.info("[OrchestrationEngine] 所有规则缓存已刷新");
        }
    }

    /**
     * 动作执行结果封装
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ActionExecutionResult implements java.io.Serializable {
        private String actionId;
        private boolean success;
        private String errorMessage;
        private Long durationMs;
        private Object result;
        private Integer retryCount;

        public static ActionExecutionResult success(String actionId, Long durationMs, Object result) {
            return ActionExecutionResult.builder()
                    .actionId(actionId)
                    .success(true)
                    .durationMs(durationMs)
                    .result(result)
                    .build();
        }

        public static ActionExecutionResult failure(String actionId, String errorMessage, Long durationMs) {
            return ActionExecutionResult.builder()
                    .actionId(actionId)
                    .success(false)
                    .errorMessage(errorMessage)
                    .durationMs(durationMs)
                    .build();
        }
    }
}
