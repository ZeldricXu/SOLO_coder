package com.edgescheduler.modules.rules.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edgescheduler.common.exception.BusinessException;
import com.edgescheduler.common.util.IdGenerator;
import com.edgescheduler.modules.rules.domain.RuleDefinition;
import com.edgescheduler.modules.rules.mapper.RuleDefinitionMapper;
import com.alibaba.fastjson2.JSON;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuleEngineService {

    private final RuleDefinitionMapper ruleDefinitionMapper;
    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final MeterRegistry meterRegistry;

    private final Map<String, RuleDefinition> loadedRules = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<Map<String, Object>> eventQueue = new LinkedBlockingQueue<>(10000);

    @Transactional(rollbackFor = Exception.class)
    public Mono<RuleDefinition> createRule(RuleDefinition rule) {
        rule.setRuleId(IdGenerator.generateRuleId());
        rule.setEnabled(true);
        rule.setTriggerCount(0L);

        ruleDefinitionMapper.insert(rule);

        if (rule.getEnabled()) {
            loadedRules.put(rule.getRuleId(), rule);
        }

        updateMetrics("rule_created");
        return Mono.just(rule);
    }

    @Transactional(rollbackFor = Exception.class)
    public Mono<RuleDefinition> updateRule(String ruleId, RuleDefinition ruleUpdates) {
        RuleDefinition rule = getRule(ruleId);

        if (ruleUpdates.getRuleName() != null) {
            rule.setRuleName(ruleUpdates.getRuleName());
        }
        if (ruleUpdates.getRuleDescription() != null) {
            rule.setRuleDescription(ruleUpdates.getRuleDescription());
        }
        if (ruleUpdates.getTriggerCondition() != null) {
            rule.setTriggerCondition(ruleUpdates.getTriggerCondition());
        }
        if (ruleUpdates.getActionDefinition() != null) {
            rule.setActionDefinition(ruleUpdates.getActionDefinition());
        }
        if (ruleUpdates.getPriority() != null) {
            rule.setPriority(ruleUpdates.getPriority());
        }
        if (ruleUpdates.getExecutionMode() != null) {
            rule.setExecutionMode(ruleUpdates.getExecutionMode());
        }

        ruleDefinitionMapper.updateById(rule);

        if (rule.getEnabled()) {
            loadedRules.put(ruleId, rule);
        }

        return Mono.just(rule);
    }

    @Transactional(rollbackFor = Exception.class)
    public Mono<RuleDefinition> toggleRule(String ruleId, boolean enabled) {
        RuleDefinition rule = getRule(ruleId);
        rule.setEnabled(enabled);
        ruleDefinitionMapper.updateById(rule);

        if (enabled) {
            loadedRules.put(ruleId, rule);
        } else {
            loadedRules.remove(ruleId);
        }

        updateMetrics(enabled ? "rule_enabled" : "rule_disabled");
        return Mono.just(rule);
    }

    public Mono<RuleDefinition> getRule(String ruleId) {
        RuleDefinition rule = ruleDefinitionMapper.selectOne(
                new LambdaQueryWrapper<RuleDefinition>().eq(RuleDefinition::getRuleId, ruleId));
        if (rule == null) {
            return Mono.error(new BusinessException("规则不存在"));
        }
        return Mono.just(rule);
    }

    public Flux<RuleDefinition> getRules(String ruleType, Boolean enabled) {
        List<RuleDefinition> rules = ruleDefinitionMapper.selectList(
                new LambdaQueryWrapper<RuleDefinition>()
                        .eq(ruleType != null, RuleDefinition::getRuleType, ruleType)
                        .eq(enabled != null, RuleDefinition::getEnabled, enabled)
                        .orderByDesc(RuleDefinition::getPriority)
                        .orderByDesc(RuleDefinition::getCreatedAt));
        return Flux.fromIterable(rules);
    }

    @Transactional(rollbackFor = Exception.class)
    public Mono<Void> deleteRule(String ruleId) {
        RuleDefinition rule = getRule(ruleId);
        ruleDefinitionMapper.deleteById(rule.getId());
        loadedRules.remove(ruleId);
        updateMetrics("rule_deleted");
        return Mono.empty();
    }

    public Mono<Boolean> evaluateRule(String ruleId, Map<String, Object> context) {
        RuleDefinition rule = getRule(ruleId).block();
        if (rule == null || !rule.getEnabled()) {
            return Mono.just(false);
        }

        boolean triggered = evaluateCondition(rule.getTriggerCondition(), context);
        if (triggered) {
            executeAction(rule, context);
        }

        return Mono.just(triggered);
    }

    public Mono<Void> ingestEvent(Map<String, Object> event) {
        if (!eventQueue.offer(event)) {
            log.warn("Event queue is full, dropping event: {}", event);
        }
        return Mono.empty();
    }

    @Scheduled(fixedDelay = 100)
    public void processEventQueue() {
        List<Map<String, Object>> events = new ArrayList<>();
        eventQueue.drainTo(events, 100);

        for (Map<String, Object> event : events) {
            processEvent(event)
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe();
        }
    }

    private Mono<Void> processEvent(Map<String, Object> event) {
        return Mono.fromCallable(() -> {
            List<RuleDefinition> sortedRules = new ArrayList<>(loadedRules.values());
            sortedRules.sort(Comparator.comparingInt(RuleDefinition::getPriority).reversed());

            for (RuleDefinition rule : sortedRules) {
                try {
                    if (evaluateCondition(rule.getTriggerCondition(), event)) {
                        if ("SYNC".equals(rule.getExecutionMode())) {
                            executeAction(rule, event);
                        } else {
                            executeActionAsync(rule, event);
                        }

                        updateRuleStats(rule, true, null);
                        updateMetrics("rule_triggered");
                    }
                } catch (Exception e) {
                    log.error("Error processing rule: {}", rule.getRuleId(), e);
                    updateRuleStats(rule, false, e.getMessage());
                    updateMetrics("rule_error");
                }
            }
            return null;
        });
    }

    private boolean evaluateCondition(Map<String, Object> condition, Map<String, Object> context) {
        if (condition == null || condition.isEmpty()) {
            return true;
        }

        String operator = (String) condition.get("operator");
        if (operator == null) {
            return evaluateSimpleCondition(condition, context);
        }

        List<Map<String, Object>> conditions = (List<Map<String, Object>>) condition.get("conditions");
        if (conditions == null) {
            return false;
        }

        return switch (operator.toUpperCase()) {
            case "AND" -> conditions.stream().allMatch(c -> evaluateCondition(c, context));
            case "OR" -> conditions.stream().anyMatch(c -> evaluateCondition(c, context));
            case "NOT" -> !evaluateCondition(conditions.get(0), context);
            default -> false;
        };
    }

    private boolean evaluateSimpleCondition(Map<String, Object> condition, Map<String, Object> context) {
        String field = (String) condition.get("field");
        String operator = (String) condition.get("operator");
        Object expectedValue = condition.get("value");

        Object actualValue = getNestedValue(context, field);
        if (actualValue == null) {
            return false;
        }

        return switch (operator.toUpperCase()) {
            case "EQUALS", "==" -> actualValue.equals(expectedValue);
            case "NOT_EQUALS", "!=" -> !actualValue.equals(expectedValue);
            case "GREATER_THAN", ">" -> compareValues(actualValue, expectedValue) > 0;
            case "LESS_THAN", "<" -> compareValues(actualValue, expectedValue) < 0;
            case "GREATER_OR_EQUAL", ">=" -> compareValues(actualValue, expectedValue) >= 0;
            case "LESS_OR_EQUAL", "<=" -> compareValues(actualValue, expectedValue) <= 0;
            case "CONTAINS" -> actualValue.toString().contains(expectedValue.toString());
            case "STARTS_WITH" -> actualValue.toString().startsWith(expectedValue.toString());
            case "ENDS_WITH" -> actualValue.toString().endsWith(expectedValue.toString());
            case "IN" -> {
                List<?> list = (List<?>) expectedValue;
                yield list.contains(actualValue);
            }
            case "BETWEEN" -> {
                List<?> range = (List<?>) expectedValue;
                double val = ((Number) actualValue).doubleValue();
                double min = ((Number) range.get(0)).doubleValue();
                double max = ((Number) range.get(1)).doubleValue();
                yield val >= min && val <= max;
            }
            default -> false;
        };
    }

    private int compareValues(Object actual, Object expected) {
        if (actual instanceof Number && expected instanceof Number) {
            double actualDouble = ((Number) actual).doubleValue();
            double expectedDouble = ((Number) expected).doubleValue();
            return Double.compare(actualDouble, expectedDouble);
        }
        return actual.toString().compareTo(expected.toString());
    }

    private Object getNestedValue(Map<String, Object> context, String field) {
        if (field == null || !field.contains(".")) {
            return context.get(field);
        }

        String[] parts = field.split("\\.");
        Object current = context;
        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    private void executeAction(RuleDefinition rule, Map<String, Object> context) {
        Map<String, Object> action = rule.getActionDefinition();
        if (action == null || action.isEmpty()) {
            return;
        }

        String actionType = (String) action.get("type");
        Map<String, Object> params = (Map<String, Object>) action.get("params");

        switch (actionType != null ? actionType.toUpperCase() : "") {
            case "HTTP_REQUEST":
                executeHttpRequest(params, context);
                break;
            case "DEVICE_COMMAND":
                executeDeviceCommand(params, context);
                break;
            case "ALERT":
                executeAlert(params, context);
                break;
            case "NOTIFICATION":
                executeNotification(params, context);
                break;
            case "DATA_FORWARD":
                executeDataForward(params, context);
                break;
            default:
                log.warn("Unknown action type: {}", actionType);
        }

        log.info("Rule executed: {}, action: {}", rule.getRuleId(), actionType);
    }

    private void executeActionAsync(RuleDefinition rule, Map<String, Object> context) {
        Mono.fromRunnable(() -> executeAction(rule, context))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(e -> log.error("Async action execution failed", e))
                .subscribe();
    }

    private void executeHttpRequest(Map<String, Object> params, Map<String, Object> context) {
        String url = (String) params.get("url");
        String method = (String) params.getOrDefault("method", "POST");
        log.info("Executing HTTP {} request to: {}", method, url);
        redisTemplate.convertAndSend("rule:action:http",
                Map.of("url", url, "method", method, "context", context)).subscribe();
    }

    private void executeDeviceCommand(Map<String, Object> params, Map<String, Object> context) {
        String deviceId = (String) params.get("deviceId");
        String command = (String) params.get("command");
        Map<String, Object> commandParams = (Map<String, Object>) params.get("params");
        log.info("Sending command {} to device: {}", command, deviceId);
        redisTemplate.convertAndSend("rule:action:device_command",
                Map.of("deviceId", deviceId, "command", command, "params", commandParams)).subscribe();
    }

    private void executeAlert(Map<String, Object> params, Map<String, Object> context) {
        String level = (String) params.getOrDefault("level", "WARNING");
        String message = (String) params.get("message");
        log.info("Alert [{}]: {}", level, message);
        redisTemplate.convertAndSend("rule:action:alert",
                Map.of("level", level, "message", message, "context", context)).subscribe();
    }

    private void executeNotification(Map<String, Object> params, Map<String, Object> context) {
        String channel = (String) params.get("channel");
        String message = (String) params.get("message");
        log.info("Sending notification to {}: {}", channel, message);
        redisTemplate.convertAndSend("rule:action:notification",
                Map.of("channel", channel, "message", message, "context", context)).subscribe();
    }

    private void executeDataForward(Map<String, Object> params, Map<String, Object> context) {
        String targetTopic = (String) params.get("targetTopic");
        Map<String, Object> data = (Map<String, Object>) params.get("data");
        log.info("Forwarding data to topic: {}", targetTopic);
        redisTemplate.convertAndSend("rule:action:data_forward",
                Map.of("topic", targetTopic, "data", data != null ? data : context)).subscribe();
    }

    @Transactional(rollbackFor = Exception.class)
    private void updateRuleStats(RuleDefinition rule, boolean success, String errorMessage) {
        rule.setTriggerCount(rule.getTriggerCount() + 1);
        rule.setLastTriggeredAt(LocalDateTime.now());
        rule.setLastExecutionResult(success ? "SUCCESS" : "FAILED");
        if (errorMessage != null) {
            rule.setLastErrorMessage(errorMessage);
        }
        ruleDefinitionMapper.updateById(rule);
    }

    @Scheduled(cron = "0 0 0 * * ?")
    @Transactional(rollbackFor = Exception.class)
    public void loadAllRules() {
        log.info("Loading all enabled rules into memory");
        List<RuleDefinition> enabledRules = ruleDefinitionMapper.selectList(
                new LambdaQueryWrapper<RuleDefinition>().eq(RuleDefinition::getEnabled, true));

        loadedRules.clear();
        for (RuleDefinition rule : enabledRules) {
            loadedRules.put(rule.getRuleId(), rule);
        }
        log.info("Loaded {} rules into memory", loadedRules.size());
    }

    public Mono<Map<String, Object>> getRuleStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("loadedRules", loadedRules.size());
        stats.put("eventQueueSize", eventQueue.size());

        long totalRules = ruleDefinitionMapper.selectCount(null);
        stats.put("totalRules", totalRules);

        long enabledRules = ruleDefinitionMapper.selectCount(
                new LambdaQueryWrapper<RuleDefinition>().eq(RuleDefinition::getEnabled, true));
        stats.put("enabledRules", enabledRules);

        return Mono.just(stats);
    }

    private void updateMetrics(String action) {
        meterRegistry.counter("edge_scheduler_rules_operations_total", "action", action).increment();
    }
}
