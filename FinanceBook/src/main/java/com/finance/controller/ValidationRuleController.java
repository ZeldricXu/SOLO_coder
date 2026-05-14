package com.finance.controller;

import com.finance.dto.ApiResponse;
import com.finance.entity.ValidationRule;
import com.finance.service.ValidationRuleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/validation-rules")
@RequiredArgsConstructor
public class ValidationRuleController {

    private final ValidationRuleService validationRuleService;
    private final ObjectMapper objectMapper;

    @GetMapping
    public ApiResponse<List<ValidationRule>> getAllActiveRules() {
        List<ValidationRule> rules = validationRuleService.getAllActiveRules();
        return ApiResponse.success(rules);
    }

    @GetMapping("/transaction-type/{typeCode}")
    public ApiResponse<List<ValidationRule>> getRulesByTransactionType(@PathVariable String typeCode) {
        List<ValidationRule> rules = validationRuleService.getRulesByTransactionType(typeCode);
        return ApiResponse.success(rules);
    }

    @GetMapping("/{ruleId}")
    public ApiResponse<ValidationRule> getRule(@PathVariable String ruleId) {
        ValidationRule rule = validationRuleService.getRuleById(ruleId);
        return ApiResponse.success(rule);
    }

    @PostMapping
    public ApiResponse<ValidationRule> createValidationRule(@RequestBody Map<String, Object> request) {
        String ruleName = (String) request.get("rule_name");
        String transactionTypeCode = (String) request.get("transaction_type_code");
        String ruleType = (String) request.get("rule_type");
        Integer priority = (Integer) request.getOrDefault("rule_priority", 100);

        Map<String, Object> configMap = (Map<String, Object>) request.get("rule_config");
        String ruleConfig = null;
        try {
            if (configMap != null) {
                ruleConfig = objectMapper.writeValueAsString(configMap);
            }
        } catch (Exception e) {
            return ApiResponse.error(400, "配置格式错误");
        }

        ValidationRule rule = validationRuleService.createValidationRule(
                ruleName, transactionTypeCode, ruleType, ruleConfig, priority);
        return ApiResponse.success(rule);
    }

    @PutMapping("/{ruleId}")
    public ApiResponse<ValidationRule> updateValidationRule(@PathVariable String ruleId, @RequestBody Map<String, Object> request) {
        String ruleConfig = null;
        Integer priority = (Integer) request.get("rule_priority");
        String status = (String) request.get("rule_status");

        Map<String, Object> configMap = (Map<String, Object>) request.get("rule_config");
        try {
            if (configMap != null) {
                ruleConfig = objectMapper.writeValueAsString(configMap);
            }
        } catch (Exception e) {
            return ApiResponse.error(400, "配置格式错误");
        }

        ValidationRule rule = validationRuleService.updateValidationRule(ruleId, ruleConfig, priority, status);
        return ApiResponse.success(rule);
    }
}
