package com.finance.service;

import com.finance.dto.RecordCreateRequest;
import com.finance.entity.ValidationRule;
import com.finance.exception.FinanceException;
import com.finance.repository.ValidationRuleRepository;
import com.finance.util.IdGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ValidationRuleService {

    private final ValidationRuleRepository validationRuleRepository;
    private final TransactionTypeService transactionTypeService;
    private final CategoryService categoryService;
    private final ObjectMapper objectMapper;

    @Transactional
    public ValidationRule createValidationRule(String ruleName, String transactionTypeCode,
                                                String ruleType, String ruleConfig,
                                                Integer priority) {
        ValidationRule rule = ValidationRule.builder()
                .ruleId(IdGenerator.generateId("vrule"))
                .ruleName(ruleName)
                .transactionTypeCode(transactionTypeCode)
                .ruleType(ruleType)
                .ruleConfig(ruleConfig)
                .rulePriority(priority != null ? priority : 100)
                .ruleStatus("active")
                .createdAt(LocalDateTime.now())
                .build();

        ValidationRule saved = validationRuleRepository.save(rule);
        log.info("创建校验规则成功: ruleName={}, typeCode={}", ruleName, transactionTypeCode);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<ValidationRule> getRulesByTransactionType(String transactionTypeCode) {
        return validationRuleRepository.findByTransactionTypeCodeAndRuleStatusOrderByRulePriorityAsc(
                transactionTypeCode, "active");
    }

    @Transactional(readOnly = true)
    public List<ValidationRule> getAllActiveRules() {
        return validationRuleRepository.findByRuleStatus("active");
    }

    @Transactional(readOnly = true)
    public ValidationRule getRuleById(String ruleId) {
        return validationRuleRepository.findById(ruleId)
                .orElseThrow(() -> new FinanceException(404, "校验规则不存在: " + ruleId));
    }

    @Transactional
    public ValidationRule updateValidationRule(String ruleId, String ruleConfig, Integer priority, String status) {
        ValidationRule rule = getRuleById(ruleId);

        if (ruleConfig != null) rule.setRuleConfig(ruleConfig);
        if (priority != null) rule.setRulePriority(priority);
        if (status != null) rule.setRuleStatus(status);
        rule.setUpdatedAt(LocalDateTime.now());

        return validationRuleRepository.save(rule);
    }

    public void validateRecord(RecordCreateRequest request) {
        String typeCode = request.getRecord_type();

        if (!transactionTypeService.isValidTransactionType(typeCode)) {
            throw FinanceException.invalidRecordType(typeCode);
        }

        List<ValidationRule> rules = getRulesByTransactionType(typeCode);

        for (ValidationRule rule : rules) {
            executeRule(rule, request);
        }

        log.debug("记录校验通过: typeCode={}", typeCode);
    }

    private void executeRule(ValidationRule rule, RecordCreateRequest request) {
        String ruleType = rule.getRuleType();
        Map<String, Object> config = parseConfig(rule.getRuleConfig());

        switch (ruleType) {
            case "SOURCE_VALIDATION":
                validateSource(request, config);
                break;
            case "CATEGORY_VALIDATION":
                validateCategory(request, config);
                break;
            case "AMOUNT_VALIDATION":
                validateAmount(request, config);
                break;
            case "CUSTOM_VALIDATION":
                validateCustom(request, config);
                break;
            default:
                log.warn("未知的校验规则类型: {}", ruleType);
        }
    }

    private void validateSource(RecordCreateRequest request, Map<String, Object> config) {
        String category = request.getRecord_category();
        List<String> allowedSources = (List<String>) config.get("allowed_sources");

        if (allowedSources != null && !allowedSources.isEmpty()) {
            boolean sourceValid = allowedSources.contains(category);
            if (!sourceValid && Boolean.TRUE.equals(config.get("require_valid_source"))) {
                throw new FinanceException(400, "无效的收入来源: " + category);
            }
        }

        if (Boolean.TRUE.equals(config.get("require_non_empty_source"))) {
            if (category == null || category.trim().isEmpty()) {
                throw new FinanceException(400, "收入来源不能为空");
            }
        }

        log.debug("来源校验通过: category={}", category);
    }

    private void validateCategory(RecordCreateRequest request, Map<String, Object> config) {
        String category = request.getRecord_category();

        if (Boolean.TRUE.equals(config.get("require_valid_category"))) {
            if (!categoryService.existsByName(category)) {
                throw new FinanceException(400, "无效的支出分类: " + category);
            }
        }

        List<String> requiredCategories = (List<String>) config.get("required_categories");
        if (requiredCategories != null && !requiredCategories.contains(category)) {
            if (Boolean.TRUE.equals(config.get("strict_mode"))) {
                throw new FinanceException(400, "分类不在允许列表中: " + category);
            }
        }

        log.debug("分类校验通过: category={}", category);
    }

    private void validateAmount(RecordCreateRequest request, Map<String, Object> config) {
        java.math.BigDecimal amount = request.getRecord_amount();

        if (config.containsKey("min_amount")) {
            java.math.BigDecimal minAmount = new java.math.BigDecimal(config.get("min_amount").toString());
            if (amount.compareTo(minAmount) < 0) {
                throw new FinanceException(400, "金额不能小于: " + minAmount);
            }
        }

        if (config.containsKey("max_amount")) {
            java.math.BigDecimal maxAmount = new java.math.BigDecimal(config.get("max_amount").toString());
            if (amount.compareTo(maxAmount) > 0) {
                throw new FinanceException(400, "金额不能超过: " + maxAmount);
            }
        }

        log.debug("金额校验通过: amount={}", amount);
    }

    private void validateCustom(RecordCreateRequest request, Map<String, Object> config) {
        String expression = (String) config.get("expression");
        if (expression != null && !expression.isEmpty()) {
            boolean result = evaluateExpression(expression, request);
            if (!result) {
                String errorMessage = (String) config.getOrDefault("error_message", "自定义校验失败");
                throw new FinanceException(400, errorMessage);
            }
        }

        log.debug("自定义校验通过");
    }

    private boolean evaluateExpression(String expression, RecordCreateRequest request) {
        Map<String, Object> context = new HashMap<>();
        context.put("accountId", request.getAccount_id());
        context.put("recordType", request.getRecord_type());
        context.put("amount", request.getRecord_amount());
        context.put("category", request.getRecord_category());
        context.put("description", request.getRecord_desc());

        if ("amount > 0".equals(expression)) {
            return request.getRecord_amount().compareTo(java.math.BigDecimal.ZERO) > 0;
        }

        return true;
    }

    private Map<String, Object> parseConfig(String configJson) {
        if (configJson == null || configJson.isEmpty()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(configJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("解析校验规则配置失败: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    public Map<String, Object> createSourceValidationConfig(List<String> allowedSources, boolean requireValid, boolean requireNonEmpty) {
        Map<String, Object> config = new HashMap<>();
        config.put("allowed_sources", allowedSources);
        config.put("require_valid_source", requireValid);
        config.put("require_non_empty_source", requireNonEmpty);
        return config;
    }

    public Map<String, Object> createCategoryValidationConfig(boolean requireValid, boolean strictMode, List<String> requiredCategories) {
        Map<String, Object> config = new HashMap<>();
        config.put("require_valid_category", requireValid);
        config.put("strict_mode", strictMode);
        config.put("required_categories", requiredCategories);
        return config;
    }

    public Map<String, Object> createAmountValidationConfig(java.math.BigDecimal minAmount, java.math.BigDecimal maxAmount) {
        Map<String, Object> config = new HashMap<>();
        if (minAmount != null) config.put("min_amount", minAmount);
        if (maxAmount != null) config.put("max_amount", maxAmount);
        return config;
    }
}
