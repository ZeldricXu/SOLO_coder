package com.edgescheduler.ruleengine.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.script.ScriptUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.edgescheduler.common.exception.BusinessException;
import com.edgescheduler.common.exception.OptimisticLockException;
import com.edgescheduler.ruleengine.dto.RuleDTO;
import com.edgescheduler.ruleengine.dto.RuleTriggerRequest;
import com.edgescheduler.ruleengine.entity.Rule;
import com.edgescheduler.ruleengine.entity.RuleExecution;
import com.edgescheduler.ruleengine.mapper.RuleExecutionMapper;
import com.edgescheduler.ruleengine.mapper.RuleMapper;
import com.edgescheduler.ruleengine.service.RuleService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuleServiceImpl implements RuleService {

    private static final BigDecimal PROGRESS_ZERO = BigDecimal.ZERO;
    private static final BigDecimal PROGRESS_COMPLETE = BigDecimal.valueOf(100);
    private static final Map<String, Object> EMPTY_CONTEXT = Collections.emptyMap();

    private final RuleMapper ruleMapper;
    private final RuleExecutionMapper executionMapper;
    private final MeterRegistry meterRegistry;

    private final Map<String, ReentrantLock> ruleLocks = new ConcurrentHashMap<>();

    @Value("${edge.scheduler.rule.retry-max-attempts:3}")
    private int maxRetryAttempts;

    @Value("${edge.scheduler.rule.retry-interval:1000}")
    private long retryInterval;

    private Counter ruleCreateCounter;
    private Counter ruleExecutionSuccessCounter;
    private Counter ruleExecutionFailedCounter;
    private Counter ruleExecutionSkippedCounter;
    private Map<String, Counter> actionCounters;

    @PostConstruct
    public void initMetrics() {
        ruleCreateCounter = meterRegistry.counter("rule.create.total");
        ruleExecutionSuccessCounter = meterRegistry.counter("rule.execution.success");
        ruleExecutionFailedCounter = meterRegistry.counter("rule.execution.failed");
        ruleExecutionSkippedCounter = meterRegistry.counter("rule.execution.skipped");
        actionCounters = new ConcurrentHashMap<>();
    }

    @Override
    @Transactional
    public RuleDTO createRule(RuleDTO ruleDTO) {
        Rule rule = new Rule();
        BeanUtils.copyProperties(ruleDTO, rule);
        rule.setRuleId("rule_" + IdUtil.getSnowflakeNextIdStr());
        rule.setVersion(1);
        rule.setEnabled(rule.getEnabled() == null ? 1 : rule.getEnabled());

        ruleMapper.insert(rule);
        ruleCreateCounter.increment();
        log.info("Rule created: {}", rule.getRuleId());

        return convertToDTO(rule);
    }

    @Override
    public RuleDTO getRule(String ruleId) {
        return convertToDTO(getRuleEntity(ruleId));
    }

    @Override
    public IPage<RuleDTO> listRules(Page<Rule> page, String triggerType, Integer enabled) {
        LambdaQueryWrapper<Rule> wrapper = buildRuleQueryWrapper(triggerType, enabled);
        return ruleMapper.selectPage(page, wrapper).convert(this::convertToDTO);
    }

    private LambdaQueryWrapper<Rule> buildRuleQueryWrapper(String triggerType, Integer enabled) {
        LambdaQueryWrapper<Rule> wrapper = new LambdaQueryWrapper<>();
        if (triggerType != null) {
            wrapper.eq(Rule::getTriggerType, triggerType);
        }
        if (enabled != null) {
            wrapper.eq(Rule::getEnabled, enabled);
        }
        wrapper.orderByDesc(Rule::getCreatedAt);
        return wrapper;
    }

    @Override
    @Transactional
    public RuleDTO updateRule(String ruleId, RuleDTO ruleDTO) {
        Rule rule = getRuleEntity(ruleId);
        updateRuleFields(rule, ruleDTO);
        rule.setVersion(rule.getVersion() + 1);
        ruleMapper.updateById(rule);
        log.info("Rule updated: {}", ruleId);
        return convertToDTO(rule);
    }

    private void updateRuleFields(Rule target, RuleDTO source) {
        if (source.getRuleName() != null) {
            target.setRuleName(source.getRuleName());
        }
        if (source.getDescription() != null) {
            target.setDescription(source.getDescription());
        }
        if (source.getTriggerType() != null) {
            target.setTriggerType(source.getTriggerType());
        }
        if (source.getTriggerConfig() != null) {
            target.setTriggerConfig(source.getTriggerConfig());
        }
        if (source.getConditionExpression() != null) {
            target.setConditionExpression(source.getConditionExpression());
        }
        if (source.getActionType() != null) {
            target.setActionType(source.getActionType());
        }
        if (source.getActionConfig() != null) {
            target.setActionConfig(source.getActionConfig());
        }
    }

    @Override
    @Transactional
    public RuleDTO setRuleEnabled(String ruleId, boolean enabled) {
        return executeWithRetry(ruleId, () -> {
            Rule rule = getRuleEntity(ruleId);
            int intEnabled = enabled ? 1 : 0;
            int updated = ruleMapper.updateEnabledWithVersion(ruleId, intEnabled, rule.getVersion());
            if (updated <= 0) {
                return null;
            }
            rule.setEnabled(intEnabled);
            rule.setVersion(rule.getVersion() + 1);
            log.info("Rule {} enabled status changed to {}", ruleId, enabled);
            return convertToDTO(rule);
        }, "Failed to update rule enabled status: " + ruleId);
    }

    private <T> T executeWithRetry(String ruleId, Supplier<T> action, String errorMessage) {
        for (int attempt = 0; attempt < maxRetryAttempts; attempt++) {
            T result = action.get();
            if (result != null) {
                return result;
            }
            sleepQuietly(retryInterval);
        }
        throw new OptimisticLockException(errorMessage + " after " + maxRetryAttempts + " attempts");
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    @Transactional
    public void deleteRule(String ruleId) {
        Rule rule = getRuleEntity(ruleId);
        ruleMapper.deleteById(rule.getId());
        ruleLocks.remove(ruleId);
        log.info("Rule deleted: {}", ruleId);
    }

    @Override
    @Transactional
    public RuleExecution triggerRule(RuleTriggerRequest request) {
        Rule rule = getRuleEntity(request.getRuleId());
        validateRuleEnabled(rule);

        RuleExecution execution = initExecution(request);
        executionMapper.insert(execution);

        try {
            Map<String, Object> context = buildExecutionContext(request, rule);

            if (!evaluateRuleCondition(rule, context)) {
                completeWithSkip(execution);
                return execution;
            }

            Map<String, Object> actionResult = executeRuleAction(rule, context);
            completeWithSuccess(execution, actionResult);
            log.info("Rule executed successfully: {}, runId: {}", rule.getRuleId(), execution.getRunId());

        } catch (Exception e) {
            completeWithFailure(execution, e);
            log.error("Rule execution failed: {}, runId: {}, error: {}", rule.getRuleId(), execution.getRunId(), e.getMessage());
        }

        return execution;
    }

    private void validateRuleEnabled(Rule rule) {
        if (rule.getEnabled() != 1) {
            throw BusinessException.badRequest("Rule is disabled: " + rule.getRuleId());
        }
    }

    private RuleExecution initExecution(RuleTriggerRequest request) {
        RuleExecution execution = new RuleExecution();
        execution.setRunId("run_" + IdUtil.getSnowflakeNextIdStr());
        execution.setRuleId(request.getRuleId());
        execution.setDeviceKey(request.getDeviceKey());
        execution.setPhase(RuleExecution.Phase.EXECUTING);
        execution.setProgress(PROGRESS_ZERO);
        execution.setTriggerData(request.getTriggerData());
        execution.setStartedAt(LocalDateTime.now());
        return execution;
    }

    private Map<String, Object> buildExecutionContext(RuleTriggerRequest request, Rule rule) {
        Map<String, Object> triggerData = request.getTriggerData();
        if (triggerData == null || triggerData.isEmpty()) {
            return Map.of(
                    "ruleId", rule.getRuleId(),
                    "deviceKey", request.getDeviceKey(),
                    "timestamp", System.currentTimeMillis()
            );
        }

        Map<String, Object> context = new HashMap<>(triggerData.size() + 3);
        context.putAll(triggerData);
        context.put("ruleId", rule.getRuleId());
        context.put("deviceKey", request.getDeviceKey());
        context.put("timestamp", System.currentTimeMillis());
        return context;
    }

    private boolean evaluateRuleCondition(Rule rule, Map<String, Object> context) {
        String expression = rule.getConditionExpression();
        if (expression == null || expression.isEmpty()) {
            return true;
        }
        Map<String, Object> evalResult = evaluateCondition(expression, context);
        return Boolean.TRUE.equals(evalResult.get("result"));
    }

    private void completeWithSkip(RuleExecution execution) {
        execution.setPhase(RuleExecution.Phase.COMPLETED);
        execution.setProgress(PROGRESS_COMPLETE);
        execution.setResultData(Map.of(
                "conditionMet", false,
                "message", "Condition not met, action skipped"
        ));
        execution.setCompletedAt(LocalDateTime.now());
        executionMapper.updateById(execution);
        ruleExecutionSkippedCounter.increment();
    }

    private void completeWithSuccess(RuleExecution execution, Map<String, Object> actionResult) {
        execution.setPhase(RuleExecution.Phase.COMPLETED);
        execution.setProgress(PROGRESS_COMPLETE);
        execution.setResultData(actionResult);
        execution.setCompletedAt(LocalDateTime.now());
        executionMapper.updateById(execution);
        ruleExecutionSuccessCounter.increment();
    }

    private void completeWithFailure(RuleExecution execution, Exception e) {
        execution.setPhase(RuleExecution.Phase.FAILED);
        execution.setErrorDetail(e.getMessage());
        execution.setCompletedAt(LocalDateTime.now());
        executionMapper.updateById(execution);
        ruleExecutionFailedCounter.increment();
    }

    @Override
    public RuleExecution getExecutionStatus(String runId) {
        RuleExecution execution = executionMapper.selectByRunId(runId);
        if (execution == null) {
            throw BusinessException.notFound("Execution not found: " + runId);
        }
        return execution;
    }

    @Override
    public List<RuleExecution> getRuleExecutions(String ruleId, int limit) {
        return executionMapper.selectByRuleId(ruleId, limit);
    }

    @Override
    @Scheduled(fixedDelayString = "${edge.scheduler.rule.timer-interval:60000}")
    public void executeTimerRules() {
        List<Rule> timerRules = ruleMapper.selectByTriggerType(Rule.TriggerType.TIMER);
        for (Rule rule : timerRules) {
            if (rule.getEnabled() == 1) {
                executeTimerRuleSafely(rule);
            }
        }
    }

    private void executeTimerRuleSafely(Rule rule) {
        ReentrantLock lock = ruleLocks.computeIfAbsent(rule.getRuleId(), k -> new ReentrantLock());
        if (!lock.tryLock()) {
            log.debug("Rule {} is already executing, skipping", rule.getRuleId());
            return;
        }
        try {
            RuleTriggerRequest request = new RuleTriggerRequest();
            request.setRuleId(rule.getRuleId());
            request.setTriggerData(Map.of("triggerType", "timer"));
            triggerRule(request);
        } catch (Exception e) {
            log.error("Timer rule execution failed: {}", rule.getRuleId(), e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Map<String, Object> evaluateCondition(String expression, Map<String, Object> context) {
        try {
            Map<String, Object> safeContext = context != null ? context : EMPTY_CONTEXT;
            Map<String, Object> bindings = new HashMap<>(safeContext);
            Object result = ScriptUtil.eval(expression, bindings);
            return Map.of(
                    "result", result != null && Boolean.parseBoolean(result.toString()),
                    "expression", expression,
                    "context", safeContext
            );
        } catch (Exception e) {
            log.error("Condition evaluation failed: {}, error: {}", expression, e.getMessage());
            throw BusinessException.validationError("Condition evaluation failed: " + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> executeAction(Rule rule, Map<String, Object> context) {
        String actionType = rule.getActionType();
        Map<String, Object> result = new HashMap<>(8);
        result.put("actionType", actionType);
        result.put("ruleId", rule.getRuleId());
        result.put("executedAt", LocalDateTime.now().toString());

        switch (actionType) {
            case Rule.ActionType.COMMAND -> handleCommandAction(rule, result);
            case Rule.ActionType.NOTIFY -> handleNotifyAction(rule, result);
            case Rule.ActionType.MQTT -> handleMqttAction(rule, result);
            case Rule.ActionType.HTTP -> handleHttpAction(rule, result);
            default -> throw BusinessException.validationError("Unsupported action type: " + actionType);
        }

        getActionCounter(actionType).increment();
        return result;
    }

    private Counter getActionCounter(String actionType) {
        return actionCounters.computeIfAbsent(actionType,
                type -> meterRegistry.counter("rule.action.executed", "actionType", type));
    }

    private void handleCommandAction(Rule rule, Map<String, Object> result) {
        result.put("status", "command_sent");
        result.put("command", rule.getActionConfig());
    }

    private void handleNotifyAction(Rule rule, Map<String, Object> result) {
        result.put("status", "notification_sent");
        result.put("notification", rule.getActionConfig());
    }

    private void handleMqttAction(Rule rule, Map<String, Object> result) {
        result.put("status", "mqtt_published");
        result.put("mqtt", rule.getActionConfig());
    }

    private void handleHttpAction(Rule rule, Map<String, Object> result) {
        result.put("status", "http_invoked");
        result.put("http", rule.getActionConfig());
    }

    private Rule getRuleEntity(String ruleId) {
        Rule rule = ruleMapper.selectByRuleId(ruleId);
        if (rule == null) {
            throw BusinessException.notFound("Rule not found: " + ruleId);
        }
        return rule;
    }

    private RuleDTO convertToDTO(Rule rule) {
        RuleDTO dto = new RuleDTO();
        BeanUtils.copyProperties(rule, dto);
        return dto;
    }
}
