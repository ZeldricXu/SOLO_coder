package com.dynamiclog.ruleengine.service;

import com.dynamiclog.common.entity.Rule;
import com.dynamiclog.common.enums.RuleConditionType;
import com.dynamiclog.common.event.DomainEvent;
import com.dynamiclog.common.exception.ResourceNotFoundException;
import com.dynamiclog.common.util.IdGenerator;
import com.dynamiclog.common.util.JsonUtils;
import com.dynamiclog.persistence.mapper.RuleMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuleEngineService {

    private final RuleMapper ruleMapper;

    private final Cache<String, List<Rule>> ruleCache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(1000)
            .build();

    private final Map<String, Pattern> patternCache = new ConcurrentHashMap<>();

    public Mono<Rule> createRule(Rule rule) {
        return Mono.fromCallable(() -> {
            rule.setId(IdGenerator.generateId("rule"));
            rule.setEnabled(rule.getEnabled() != null ? rule.getEnabled() : true);
            ruleMapper.insert(rule);
            invalidateCache(rule.getEventType());
            log.info("Rule created: id={}, name={}, eventType={}", rule.getId(), rule.getName(), rule.getEventType());
            return rule;
        });
    }

    public Mono<Rule> getRule(String ruleId) {
        return Mono.fromCallable(() -> {
            Rule rule = ruleMapper.selectById(ruleId);
            if (rule == null) {
                throw new ResourceNotFoundException("Rule", ruleId);
            }
            return rule;
        });
    }

    public Flux<Rule> getRulesByEventType(String eventType) {
        return Mono.fromCallable(() -> {
            List<Rule> rules = ruleCache.getIfPresent(eventType);
            if (rules == null) {
                rules = ruleMapper.findByEventType(eventType);
                ruleCache.put(eventType, rules);
            }
            return rules;
        }).flatMapMany(Flux::fromIterable);
    }

    public Flux<Rule> getRulesByNamespace(String namespace) {
        return Mono.fromCallable(() -> ruleMapper.findByNamespace(namespace))
                .flatMapMany(Flux::fromIterable);
    }

    public Mono<Rule> updateRule(String ruleId, Rule rule) {
        return Mono.fromCallable(() -> {
            Rule existing = ruleMapper.selectById(ruleId);
            if (existing == null) {
                throw new ResourceNotFoundException("Rule", ruleId);
            }
            if (rule.getName() != null) existing.setName(rule.getName());
            if (rule.getDescription() != null) existing.setDescription(rule.getDescription());
            if (rule.getEventType() != null) {
                invalidateCache(existing.getEventType());
                existing.setEventType(rule.getEventType());
            }
            if (rule.getConditionType() != null) existing.setConditionType(rule.getConditionType());
            if (rule.getConditionExpression() != null) existing.setConditionExpression(rule.getConditionExpression());
            if (rule.getActionType() != null) existing.setActionType(rule.getActionType());
            if (rule.getActionConfig() != null) existing.setActionConfig(rule.getActionConfig());
            if (rule.getEnabled() != null) existing.setEnabled(rule.getEnabled());
            if (rule.getPriority() != null) existing.setPriority(rule.getPriority());
            ruleMapper.updateById(existing);
            invalidateCache(existing.getEventType());
            return existing;
        });
    }

    public Mono<Void> deleteRule(String ruleId) {
        return Mono.fromRunnable(() -> {
            Rule rule = ruleMapper.selectById(ruleId);
            if (rule != null) {
                ruleMapper.deleteById(ruleId);
                invalidateCache(rule.getEventType());
                log.info("Rule deleted: id={}", ruleId);
            }
        });
    }

    public Mono<Void> evaluateRules(DomainEvent event) {
        return getRulesByEventType(event.getEventType())
                .filter(Rule::getEnabled)
                .filter(rule -> evaluateCondition(rule, event))
                .flatMap(rule -> executeAction(rule, event))
                .then();
    }

    private boolean evaluateCondition(Rule rule, DomainEvent event) {
        if (rule.getConditionExpression() == null || rule.getConditionType() == null) {
            return true;
        }

        Map<String, Object> payload = event.getPayload();
        String expression = rule.getConditionExpression();

        try {
            return switch (rule.getConditionType()) {
                case EQUALS -> evaluateEquals(expression, payload);
                case NOT_EQUALS -> !evaluateEquals(expression, payload);
                case GREATER_THAN -> evaluateGreaterThan(expression, payload);
                case LESS_THAN -> evaluateLessThan(expression, payload);
                case CONTAINS -> evaluateContains(expression, payload);
                case REGEX -> evaluateRegex(expression, payload);
                default -> true;
            };
        } catch (Exception e) {
            log.error("Rule condition evaluation failed: ruleId={}", rule.getId(), e);
            return false;
        }
    }

    private boolean evaluateEquals(String expression, Map<String, Object> payload) {
        String[] parts = expression.split("==");
        if (parts.length != 2) return false;
        String key = parts[0].trim();
        String expectedValue = parts[1].trim().replace("'", "");
        Object actualValue = getNestedValue(payload, key);
        return expectedValue.equals(String.valueOf(actualValue));
    }

    private boolean evaluateGreaterThan(String expression, Map<String, Object> payload) {
        String[] parts = expression.split(">");
        if (parts.length != 2) return false;
        String key = parts[0].trim();
        double threshold = Double.parseDouble(parts[1].trim());
        Object value = getNestedValue(payload, key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue() > threshold;
        }
        return false;
    }

    private boolean evaluateLessThan(String expression, Map<String, Object> payload) {
        String[] parts = expression.split("<");
        if (parts.length != 2) return false;
        String key = parts[0].trim();
        double threshold = Double.parseDouble(parts[1].trim());
        Object value = getNestedValue(payload, key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue() < threshold;
        }
        return false;
    }

    private boolean evaluateContains(String expression, Map<String, Object> payload) {
        String[] parts = expression.split("contains");
        if (parts.length != 2) return false;
        String key = parts[0].trim();
        String substring = parts[1].trim().replace("'", "");
        Object value = getNestedValue(payload, key);
        return value != null && String.valueOf(value).contains(substring);
    }

    private boolean evaluateRegex(String expression, Map<String, Object> payload) {
        String[] parts = expression.split("matches");
        if (parts.length != 2) return false;
        String key = parts[0].trim();
        String regex = parts[1].trim().replace("'", "");
        Object value = getNestedValue(payload, key);
        if (value == null) return false;

        Pattern pattern = patternCache.computeIfAbsent(regex, Pattern::compile);
        return pattern.matcher(String.valueOf(value)).matches();
    }

    private Object getNestedValue(Map<String, Object> map, String path) {
        String[] keys = path.split("\\.");
        Object current = map;
        for (String key : keys) {
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(key);
            } else {
                return null;
            }
        }
        return current;
    }

    private Mono<Void> executeAction(Rule rule, DomainEvent event) {
        return Mono.fromRunnable(() -> {
            log.info("Executing rule action: ruleId={}, actionType={}, eventId={}",
                    rule.getId(), rule.getActionType(), event.getEventId());
            switch (rule.getActionType()) {
                case "LOG" -> handleLogAction(rule, event);
                case "ALERT" -> handleAlertAction(rule, event);
                case "WEBHOOK" -> handleWebhookAction(rule, event);
                case "TRANSFORM" -> handleTransformAction(rule, event);
                default -> log.warn("Unknown action type: {}", rule.getActionType());
            }
        });
    }

    private void handleLogAction(Rule rule, DomainEvent event) {
        String level = rule.getActionConfig() != null ? rule.getActionConfig() : "INFO";
        log.atLevel(org.slf4j.event.Level.valueOf(level))
                .log("Rule triggered: {}, event: {}", rule.getName(), JsonUtils.toJson(event));
    }

    private void handleAlertAction(Rule rule, DomainEvent event) {
        log.warn("ALERT - Rule {} triggered: {}", rule.getName(), JsonUtils.toJson(event));
    }

    private void handleWebhookAction(Rule rule, DomainEvent event) {
        log.info("Webhook would be called with config: {}", rule.getActionConfig());
    }

    private void handleTransformAction(Rule rule, DomainEvent event) {
        log.info("Transform action executed with config: {}", rule.getActionConfig());
    }

    private void invalidateCache(String eventType) {
        ruleCache.invalidate(eventType);
    }
}
