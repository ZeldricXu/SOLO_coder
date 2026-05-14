package com.configcenter.validation.service;

import com.configcenter.common.exception.ConfigValidationException;
import com.configcenter.validation.config.ValidationProperties;
import com.configcenter.validation.rule.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ValidationRuleService {

    private final ValidationProperties properties;

    @Getter
    private final Map<String, ValidationRule> rules = new ConcurrentHashMap<>();
    
    @Getter
    private final Map<String, List<String>> configRuleMappings = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("Initializing validation rules...");

        addRule(KeyFormatRule.builder().build());
        addRule(ValueLengthRule.builder().build());
        addRule(JsonFormatRule.builder().build());
        addRule(NumberRangeRule.builder().build());
        addRule(SensitiveCheckRule.builder().build());
        addRule(RegexRule.builder().build());
        addRule(EnumRule.builder().build());

        if (properties.getRules() != null && !properties.getRules().isEmpty()) {
            for (ValidationProperties.ValidationRule ruleConfig : properties.getRules()) {
                ValidationRule rule = createRuleFromConfig(ruleConfig);
                if (rule != null) {
                    addRule(rule);
                    log.info("Loaded custom rule: {}", ruleConfig.getRuleId());
                }
            }
        }
        
        if (properties.getConfigRuleMap() != null) {
            configRuleMappings.putAll(properties.getConfigRuleMap());
        }
        
        if (properties.getConfigMappings() != null && !properties.getConfigMappings().isEmpty()) {
            for (ValidationProperties.ConfigValidationMapping mapping : properties.getConfigMappings()) {
                log.info("Loaded config validation mapping: {} -> {}", 
                        mapping.getConfigKey() != null ? mapping.getConfigKey() : mapping.getConfigKeyPattern(),
                        mapping.getRuleIds());
            }
        }

        log.info("Validation rules initialized, total: {}, config mappings: {}", 
                rules.size(), 
                properties.getConfigMappings() != null ? properties.getConfigMappings().size() : 0);
    }

    public void addRule(ValidationRule rule) {
        if (rule != null && rule.isEnabled()) {
            rules.put(rule.getRuleId(), rule);
        }
    }

    public void removeRule(String ruleId) {
        rules.remove(ruleId);
    }

    public ValidationRule getRule(String ruleId) {
        return rules.get(ruleId);
    }

    public List<ValidationRule> getAllRules() {
        return new ArrayList<>(rules.values());
    }

    public List<ValidationRule> getEnabledRules() {
        return rules.values().stream()
                .filter(ValidationRule::isEnabled)
                .sorted(Comparator.comparingInt(ValidationRule::getPriority))
                .collect(Collectors.toList());
    }
    
    public List<String> getRulesForConfig(String configKey, String groupId) {
        List<String> result = new ArrayList<>();
        
        if (configRuleMappings != null && configRuleMappings.containsKey(configKey)) {
            result.addAll(configRuleMappings.get(configKey));
        }
        
        if (properties != null) {
            List<String> propertyRules = properties.getRulesForConfig(configKey, groupId);
            result.addAll(propertyRules);
        }
        
        return result.stream().distinct().collect(Collectors.toList());
    }
    
    public Map<String, Object> validateWithConfigRules(String value, String configKey, String groupId, Map<String, Object> context) {
        List<String> ruleIds = getRulesForConfig(configKey, groupId);
        return validateWithRules(value, ruleIds, context);
    }
    
    public boolean hasConfigSpecificRules(String configKey, String groupId) {
        List<String> rules = getRulesForConfig(configKey, groupId);
        return !rules.isEmpty();
    }

    public Map<String, Object> validateWithRules(String value, List<String> ruleIds, Map<String, Object> context) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("errors", new ArrayList<Map<String, Object>>());
        result.put("warnings", new ArrayList<Map<String, Object>>());
        result.put("passedRules", new ArrayList<String>());
        result.put("failedRules", new ArrayList<String>());

        List<ValidationRule> rulesToApply;
        if (ruleIds == null || ruleIds.isEmpty()) {
            rulesToApply = getEnabledRules();
        } else {
            rulesToApply = ruleIds.stream()
                    .map(this::getRule)
                    .filter(Objects::nonNull)
                    .filter(ValidationRule::isEnabled)
                    .sorted(Comparator.comparingInt(ValidationRule::getPriority))
                    .collect(Collectors.toList());
        }

        for (ValidationRule rule : rulesToApply) {
            try {
                if (!shouldApplyRule(rule, context)) {
                    continue;
                }
                
                Map<String, Object> ruleContext = context != null ? 
                        new HashMap<>(context) : new HashMap<>();
                rule.validate(value, ruleContext);
                ((List<String>) result.get("passedRules")).add(rule.getRuleId());

                for (Map.Entry<String, Object> entry : ruleContext.entrySet()) {
                    if (entry.getKey().startsWith("warning_")) {
                        Map<String, Object> warning = new HashMap<>();
                        warning.put("ruleId", rule.getRuleId());
                        warning.put("ruleName", rule.getName());
                        warning.put("message", entry.getValue());
                        ((List<Map<String, Object>>) result.get("warnings")).add(warning);
                    }
                }
            } catch (ConfigValidationException e) {
                result.put("success", false);
                Map<String, Object> error = new HashMap<>();
                error.put("ruleId", rule.getRuleId());
                error.put("ruleName", rule.getName());
                error.put("ruleType", rule.getRuleType());
                error.put("message", e.getMessage());
                ((List<Map<String, Object>>) result.get("errors")).add(error);
                ((List<String>) result.get("failedRules")).add(rule.getRuleId());
            }
        }

        return result;
    }
    
    private boolean shouldApplyRule(ValidationRule rule, Map<String, Object> context) {
        String condition = null;
        if (rule.getParams() != null && rule.getParams().containsKey("condition")) {
            condition = String.valueOf(rule.getParams().get("condition"));
        }
        
        if (condition == null || condition.isEmpty()) {
            return true;
        }
        
        return evaluateCondition(condition, context);
    }
    
    private boolean evaluateCondition(String condition, Map<String, Object> context) {
        if (context == null) {
            return true;
        }
        
        try {
            if (condition.startsWith("context:")) {
                String key = condition.substring("context:".length());
                return context.containsKey(key) && context.get(key) != null;
            }
            
            if (condition.contains("==")) {
                String[] parts = condition.split("==");
                if (parts.length == 2) {
                    String key = parts[0].trim();
                    String expected = parts[1].trim().replace("\"", "").replace("'", "");
                    Object actual = context.get(key);
                    return actual != null && actual.toString().equals(expected);
                }
            }
            
            return true;
        } catch (Exception e) {
            log.warn("Failed to evaluate condition: {}", condition, e);
            return true;
        }
    }

    public Map<String, Object> validateWithRule(String value, String ruleId) {
        ValidationRule rule = getRule(ruleId);
        if (rule == null) {
            throw new ConfigValidationException("校验规则不存在: " + ruleId);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("ruleId", ruleId);
        result.put("ruleName", rule.getName());
        result.put("ruleType", rule.getRuleType());

        try {
            rule.validate(value, new HashMap<>());
            result.put("success", true);
        } catch (ConfigValidationException e) {
            result.put("success", false);
            result.put("errorMessage", e.getMessage());
        }

        return result;
    }

    public void enableRule(String ruleId) {
        ValidationRule rule = getRule(ruleId);
        if (rule != null) {
            setRuleEnabled(rule, true);
        }
    }

    public void disableRule(String ruleId) {
        ValidationRule rule = getRule(ruleId);
        if (rule != null) {
            setRuleEnabled(rule, false);
        }
    }
    
    private void setRuleEnabled(ValidationRule rule, boolean enabled) {
        if (rule instanceof KeyFormatRule) {
            ((KeyFormatRule) rule).setEnabled(enabled);
        } else if (rule instanceof ValueLengthRule) {
            ((ValueLengthRule) rule).setEnabled(enabled);
        } else if (rule instanceof JsonFormatRule) {
            ((JsonFormatRule) rule).setEnabled(enabled);
        } else if (rule instanceof NumberRangeRule) {
            ((NumberRangeRule) rule).setEnabled(enabled);
        } else if (rule instanceof SensitiveCheckRule) {
            ((SensitiveCheckRule) rule).setEnabled(enabled);
        } else if (rule instanceof RegexRule) {
            ((RegexRule) rule).setEnabled(enabled);
        } else if (rule instanceof EnumRule) {
            ((EnumRule) rule).setEnabled(enabled);
        }
    }
    
    @SuppressWarnings("unchecked")
    public void updateRuleParams(String ruleId, Map<String, Object> newParams) {
        ValidationRule rule = getRule(ruleId);
        if (rule != null && newParams != null) {
            Map<String, Object> currentParams = rule.getParams();
            if (currentParams == null) {
                currentParams = new HashMap<>();
            }
            currentParams.putAll(newParams);
            
            if (rule instanceof KeyFormatRule) {
                ((KeyFormatRule) rule).setParams(currentParams);
            } else if (rule instanceof ValueLengthRule) {
                ((ValueLengthRule) rule).setParams(currentParams);
            } else if (rule instanceof JsonFormatRule) {
                ((JsonFormatRule) rule).setParams(currentParams);
            } else if (rule instanceof NumberRangeRule) {
                ((NumberRangeRule) rule).setParams(currentParams);
            } else if (rule instanceof SensitiveCheckRule) {
                ((SensitiveCheckRule) rule).setParams(currentParams);
            } else if (rule instanceof RegexRule) {
                ((RegexRule) rule).setParams(currentParams);
            } else if (rule instanceof EnumRule) {
                ((EnumRule) rule).setParams(currentParams);
            }
        }
    }

    public Map<String, Object> getRuleStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalRules", rules.size());
        stats.put("enabledRules", (int) rules.values().stream().filter(ValidationRule::isEnabled).count());
        stats.put("disabledRules", (int) rules.values().stream().filter(r -> !r.isEnabled()).count());
        stats.put("configMappings", properties.getConfigMappings() != null ? properties.getConfigMappings().size() : 0);
        
        Map<String, Long> byType = rules.values().stream()
                .collect(Collectors.groupingBy(ValidationRule::getRuleType, Collectors.counting()));
        stats.put("rulesByType", byType);
        
        Map<String, Object> configStats = new HashMap<>();
        configStats.put("totalMappings", properties.getConfigMappings() != null ? properties.getConfigMappings().size() : 0);
        configStats.put("enabledMappings", properties.getConfigMappings() != null ? 
                (int) properties.getConfigMappings().stream()
                    .filter(m -> Boolean.TRUE.equals(m.getEnabled()))
                    .count() : 0);
        stats.put("configMappings", configStats);
        
        return stats;
    }
    
    public List<Map<String, Object>> getAllRuleDetails() {
        List<Map<String, Object>> details = new ArrayList<>();
        for (ValidationRule rule : rules.values()) {
            Map<String, Object> detail = new HashMap<>();
            detail.put("ruleId", rule.getRuleId());
            detail.put("ruleType", rule.getRuleType());
            detail.put("name", rule.getName());
            detail.put("description", rule.getDescription());
            detail.put("enabled", rule.isEnabled());
            detail.put("priority", rule.getPriority());
            detail.put("params", rule.getParams());
            detail.put("errorMessage", rule.getErrorMessage());
            details.add(detail);
        }
        return details;
    }
    
    public void addConfigRuleMapping(String configKey, List<String> ruleIds) {
        configRuleMappings.put(configKey, ruleIds);
    }
    
    public void removeConfigRuleMapping(String configKey) {
        configRuleMappings.remove(configKey);
    }

    private ValidationRule createRuleFromConfig(ValidationProperties.ValidationRule config) {
        switch (config.getRuleType()) {
            case "KEY_FORMAT":
                return KeyFormatRule.builder()
                        .ruleId(config.getRuleId())
                        .name(config.getName())
                        .description(config.getDescription())
                        .enabled(config.getEnabled())
                        .priority(config.getPriority())
                        .params(config.getParams())
                        .build();
            case "VALUE_LENGTH":
                return ValueLengthRule.builder()
                        .ruleId(config.getRuleId())
                        .name(config.getName())
                        .description(config.getDescription())
                        .enabled(config.getEnabled())
                        .priority(config.getPriority())
                        .params(config.getParams())
                        .build();
            case "JSON_FORMAT":
                return JsonFormatRule.builder()
                        .ruleId(config.getRuleId())
                        .name(config.getName())
                        .description(config.getDescription())
                        .enabled(config.getEnabled())
                        .priority(config.getPriority())
                        .params(config.getParams())
                        .build();
            case "NUMBER_RANGE":
                return NumberRangeRule.builder()
                        .ruleId(config.getRuleId())
                        .name(config.getName())
                        .description(config.getDescription())
                        .enabled(config.getEnabled())
                        .priority(config.getPriority())
                        .params(config.getParams())
                        .build();
            case "SENSITIVE_CHECK":
                return SensitiveCheckRule.builder()
                        .ruleId(config.getRuleId())
                        .name(config.getName())
                        .description(config.getDescription())
                        .enabled(config.getEnabled())
                        .priority(config.getPriority())
                        .params(config.getParams())
                        .build();
            case "REGEX":
                RegexRule.RegexRuleBuilder regexBuilder = RegexRule.builder()
                        .ruleId(config.getRuleId())
                        .name(config.getName())
                        .description(config.getDescription())
                        .enabled(config.getEnabled())
                        .priority(config.getPriority())
                        .params(config.getParams());
                if (config.getErrorMessage() != null) {
                    regexBuilder.errorMessage(config.getErrorMessage());
                }
                return regexBuilder.build();
            case "ENUM":
                EnumRule.EnumRuleBuilder enumBuilder = EnumRule.builder()
                        .ruleId(config.getRuleId())
                        .name(config.getName())
                        .description(config.getDescription())
                        .enabled(config.getEnabled())
                        .priority(config.getPriority())
                        .params(config.getParams());
                if (config.getErrorMessage() != null) {
                    enumBuilder.errorMessage(config.getErrorMessage());
                }
                return enumBuilder.build();
            default:
                log.warn("Unknown rule type: {}", config.getRuleType());
                return null;
        }
    }
}